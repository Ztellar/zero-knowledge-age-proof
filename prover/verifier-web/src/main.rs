//! Demo web verifier (decision 0005). Serves HTTPS on the local network with a self-signed
//! certificate; the page shows a QR with this server's origin, a fresh challenge and the
//! certificate's SHA-256, which the app pins instead of trusting any CA.
//!
//!     cargo run --release -p verifier-web -- [--port 8443] [--host <ip>] [--trust-mock] [--allow-no-chip] [--cert-out <path>]
//!
//! `--trust-mock` also accepts the mock card's CSCA, for testing without a real card.
//! `--allow-no-chip` accepts proofs without Active Authentication (decision 0006), which
//! anyone who once read the card's files could make; by default they are rejected.
//! `--cert-out` writes the certificate as PEM, so a client such as curl can verify it.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use age_core::Date;
use age_verifier::{verify, Policy, Presentation, CHILE_CSCAS};
use axum::body::Bytes;
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use axum_server::tls_rustls::RustlsConfig;
use qrcode::render::svg;
use qrcode::QrCode;
use serde::Serialize;
use sha2::{Digest, Sha256};

/// A QR stays answerable this long.
const SESSION_TTL: Duration = Duration::from_secs(5 * 60);
/// A presentation is ~227 KB; anything much larger is not one.
const MAX_BODY: usize = 1 << 20;

struct Session {
    challenge: [u8; 32],
    created: Instant,
    outcome: Option<Outcome>,
}

#[derive(Clone, Serialize)]
#[serde(tag = "state", rename_all = "lowercase")]
enum Outcome {
    Accepted { min_age: u8, proved_on: String, csca: String, chip_authenticated: bool },
    Rejected { reason: String },
}

struct AppState {
    origin: String,
    cert_sha256: String,
    policy: Policy,
    csca_labels: Vec<(String, [u8; 32])>,
    sessions: Mutex<HashMap<String, Session>>,
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let flag = |name: &str| args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned();
    let port: u16 = flag("--port").map(|p| p.parse().expect("--port must be a number")).unwrap_or(8443);
    let host: IpAddr = flag("--host")
        .map(|h| h.parse().expect("--host must be an IP address"))
        .unwrap_or_else(|| local_ip_address::local_ip().expect("no local IP; pass --host"));

    let mut csca_labels: Vec<(String, [u8; 32])> =
        CHILE_CSCAS.iter().map(|(label, hash)| (label.to_string(), *hash)).collect();
    if args.iter().any(|a| a == "--trust-mock") {
        let mock = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/../fixtures/out/csca_spki.der"))
            .expect("mock CSCA not found; run fixtures/gen.py");
        csca_labels.push(("cédula de prueba (mock)".into(), Sha256::digest(mock).into()));
    }

    rustls::crypto::ring::default_provider().install_default().expect("crypto provider already set");
    let cert = rcgen::generate_simple_self_signed(vec![host.to_string()]).expect("self-signed certificate");
    let cert_der = cert.cert.der().to_vec();
    if let Some(path) = flag("--cert-out") {
        std::fs::write(path, cert.cert.pem()).expect("--cert-out");
    }
    let tls = RustlsConfig::from_der(vec![cert_der.clone()], cert.signing_key.serialize_der())
        .await
        .expect("TLS config");

    let origin = match host {
        IpAddr::V4(ip) => format!("https://{ip}:{port}"),
        IpAddr::V6(ip) => format!("https://[{ip}]:{port}"),
    };
    let state = Arc::new(AppState {
        origin: origin.clone(),
        cert_sha256: hex::encode(Sha256::digest(&cert_der)),
        policy: Policy {
            trusted_cscas: csca_labels.iter().map(|(_, hash)| *hash).collect(),
            min_age: 18,
            max_proof_age_days: 365,
            require_chip_authentication: !args.iter().any(|a| a == "--allow-no-chip"),
        },
        csca_labels,
        sessions: Mutex::new(HashMap::new()),
    });

    let app = Router::new()
        .route("/", get(page))
        .route("/sessions/{id}", get(status))
        .route("/sessions/{id}/presentation", post(present))
        .layer(axum::extract::DefaultBodyLimit::max(MAX_BODY))
        .with_state(state.clone());

    println!("Verifier at {origin} (certificate sha256 {})", state.cert_sha256);
    println!("Open it in a browser on this computer or the same network; the phone must reach it too.");
    axum_server::bind_rustls(SocketAddr::new(host, port), tls).serve(app.into_make_service()).await.unwrap();
}

/// A new session per page load: a fresh challenge and its QR.
async fn page(State(state): State<Arc<AppState>>) -> Html<String> {
    let challenge: [u8; 32] = rand::random();
    let id = hex::encode(rand::random::<[u8; 16]>());
    {
        let mut sessions = state.sessions.lock().unwrap();
        sessions.retain(|_, s| s.created.elapsed() < SESSION_TTL);
        sessions.insert(id.clone(), Session { challenge, created: Instant::now(), outcome: None });
    }
    // docs/protocol.md, version 2, with the pin this demo's self-signed certificate needs.
    let url = format!("{}/sessions/{id}/presentation", state.origin);
    let link = format!(
        "zkage://present?v=2&origin={}&url={}&challenge={}&pin={}",
        percent_encode(&state.origin),
        percent_encode(&url),
        hex::encode(challenge),
        state.cert_sha256,
    );
    let qr = QrCode::new(link.as_bytes())
        .expect("QR")
        .render::<svg::Color>()
        .min_dimensions(280, 280)
        .quiet_zone(true)
        .build();
    Html(include_str!("page.html").replace("{{QR}}", &qr).replace("{{SESSION}}", &id).replace("{{ORIGIN}}", &state.origin).replace("{{LINK}}", &link.replace('&', "&amp;")))
}

async fn status(State(state): State<Arc<AppState>>, Path(id): Path<String>) -> Response {
    let sessions = state.sessions.lock().unwrap();
    match sessions.get(&id) {
        None => StatusCode::NOT_FOUND.into_response(),
        Some(Session { outcome: Some(outcome), .. }) => Json(outcome.clone()).into_response(),
        Some(_) => Json(serde_json::json!({ "state": "pending" })).into_response(),
    }
}

/// Body: `u32 LE length | receipt | u32 LE length | device key SPKI | u32 LE length | signature`.
async fn present(State(state): State<Arc<AppState>>, Path(id): Path<String>, body: Bytes) -> Response {
    let challenge = {
        let sessions = state.sessions.lock().unwrap();
        match sessions.get(&id) {
            Some(s) if s.outcome.is_none() && s.created.elapsed() < SESSION_TTL => s.challenge,
            Some(s) if s.outcome.is_some() => return (StatusCode::CONFLICT, "already answered").into_response(),
            _ => return (StatusCode::NOT_FOUND, "unknown or expired session").into_response(),
        }
    };
    let Some(presentation) = Presentation::from_body(&body) else {
        return (StatusCode::BAD_REQUEST, "malformed presentation").into_response();
    };

    let checked = state.clone();
    let result = tokio::task::spawn_blocking(move || {
        verify(&presentation, &checked.origin, &challenge, today(), &checked.policy)
    })
    .await
    .expect("verifier task");
    let outcome = match result {
        Ok(claim) => Outcome::Accepted {
            min_age: claim.min_age,
            proved_on: format!("{:04}-{:02}-{:02}", claim.today.year, claim.today.month, claim.today.day),
            csca: state
                .csca_labels
                .iter()
                .find(|(_, hash)| *hash == claim.csca_sha256)
                .map(|(label, _)| label.clone())
                .unwrap_or_default(),
            chip_authenticated: claim.chip_authenticated,
        },
        Err(rejection) => Outcome::Rejected { reason: rejection.code().into() },
    };
    if let Some(session) = state.sessions.lock().unwrap().get_mut(&id) {
        session.outcome = Some(outcome.clone());
    }
    Json(outcome).into_response()
}

/// Today in UTC, from the system clock.
fn today() -> Date {
    let days = (SystemTime::now().duration_since(UNIX_EPOCH).expect("clock before 1970").as_secs() / 86_400) as i64;
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u8;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u8;
    let year = (yoe + era * 400 + i64::from(month <= 2)) as u16;
    Date { year, month, day }
}

fn percent_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}
