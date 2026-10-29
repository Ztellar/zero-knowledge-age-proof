//! Face match on public-domain portraits. Needs the models from
//! `prover/scripts/download-face-models.sh`.

use facematch::{cosine, decode_photo, pose, FaceMatcher, SAME_PERSON_COSINE};
use image::{imageops, DynamicImage, RgbImage};
use std::path::Path;
use std::sync::OnceLock;

fn matcher() -> &'static FaceMatcher {
    static M: OnceLock<FaceMatcher> = OnceLock::new();
    M.get_or_init(|| {
        let models = Path::new(env!("CARGO_MANIFEST_DIR")).join("models");
        let read = |f: &str| {
            std::fs::read(models.join(f)).unwrap_or_else(|_| panic!("{f} missing: run prover/scripts/download-face-models.sh"))
        };
        FaceMatcher::new(&read("face_detection_yunet_2023mar.onnx"), &read("face_recognition_sface_2021dec.onnx")).unwrap()
    })
}

fn photo(name: &str) -> RgbImage {
    image::open(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/faces").join(name)).unwrap().to_rgb8()
}

/// A chip-like photo: small and grayscale, as DG2 often is.
fn chip_photo(name: &str) -> RgbImage {
    let small = imageops::resize(&photo(name), 240, 300, imageops::FilterType::Triangle);
    DynamicImage::ImageRgb8(small).grayscale().to_rgb8()
}

fn embedding(image: &RgbImage) -> Vec<f32> {
    let faces = matcher().detect(image).unwrap();
    assert_eq!(faces.len(), 1, "{faces:?}");
    matcher().embed(image, &faces[0]).unwrap()
}

#[test]
fn same_person_years_apart() {
    let score = cosine(&embedding(&photo("biden-2013.jpg")), &embedding(&photo("biden-2021.jpg")));
    println!("biden 2013 vs 2021: {score:.3}");
    assert!(score >= SAME_PERSON_COSINE);
}

#[test]
fn chip_photo_against_camera() {
    let score = cosine(&embedding(&chip_photo("biden-2013.jpg")), &embedding(&photo("biden-2021.jpg")));
    println!("biden chip-like 2013 vs 2021: {score:.3}");
    assert!(score >= SAME_PERSON_COSINE);
}

#[test]
fn different_people() {
    for other in ["biden-2013.jpg", "biden-2021.jpg"] {
        let score = cosine(&embedding(&photo("obama-2012.jpg")), &embedding(&photo(other)));
        println!("obama 2012 vs {other}: {score:.3}");
        assert!(score < SAME_PERSON_COSINE);
    }
}

#[test]
fn landmarks_and_pose_are_plausible() {
    let image = photo("biden-2021.jpg");
    let face = matcher().detect(&image).unwrap()[0];
    let [l, r, n, ml, _] = face.landmarks;
    assert!(l[0] < n[0] && n[0] < r[0], "{face:?}");
    assert!(l[1] < n[1] && n[1] < ml[1], "{face:?}");
    let p = pose(&face);
    println!("{p:?}");
    assert!(p.yaw.abs() < 0.3 && p.roll_deg.abs() < 15.0);

    // Mirroring the photo flips the yaw's sign.
    let mirrored = imageops::flip_horizontal(&image);
    let q = pose(&matcher().detect(&mirrored).unwrap()[0]);
    assert!((p.yaw + q.yaw).abs() < 0.05, "{p:?} {q:?}");
}

#[test]
fn jpeg2000_chip_photo() {
    // 240×300 JPEG 2000 made with macOS `sips` from biden-2013.jpg.
    let bytes = std::fs::read(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/faces/biden-2013-chip.jp2")).unwrap();
    let chip = decode_photo(&bytes).unwrap();
    assert_eq!(chip.dimensions(), (240, 300));
    let score = cosine(&embedding(&chip), &embedding(&photo("biden-2021.jpg")));
    println!("biden JPEG 2000 chip 2013 vs 2021: {score:.3}");
    assert!(score >= SAME_PERSON_COSINE);
}
