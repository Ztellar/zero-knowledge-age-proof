//! JNI entry points for the face match of decision 0007: a local check before proving, whose
//! result never enters the proof.
//!
//! Kotlin side: `cl.zkage.app.NativeFace`. A session holds the models, the chip photo's
//! embedding and the liveness progress; the chip photo itself is dropped once embedded.

use facematch::{cosine, decode_photo, pose, FaceMatcher, Liveness, LivenessCheck, Pose};
use image::{imageops, RgbImage, RgbaImage};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jfloatArray, jint, jlong};
use jni::JNIEnv;

struct Session {
    matcher: FaceMatcher,
    chip: Vec<f32>,
    check: LivenessCheck,
}

type JniResult<T> = Result<T, (&'static str, String)>;

fn bytes(env: &mut JNIEnv, a: &JByteArray) -> JniResult<Vec<u8>> {
    env.convert_byte_array(a).map_err(|e| ("java/lang/IllegalStateException", e.to_string()))
}

fn throw<T>(env: &mut JNIEnv, (class, message): (&str, String), empty: T) -> T {
    let _ = env.throw_new(class, message);
    empty
}

/// Loads the models and embeds the face in the chip photo (DG2's image data, JPEG or JPEG
/// 2000). Returns the session handle. Throws `IllegalArgumentException` if the photo has no
/// face.
#[no_mangle]
pub extern "system" fn Java_cl_zkage_app_NativeFace_open<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    yunet: JByteArray<'local>,
    sface: JByteArray<'local>,
    chip_photo: JByteArray<'local>,
) -> jlong {
    let result = (|| -> JniResult<Session> {
        let state = |e: String| ("java/lang/IllegalStateException", e);
        let invalid = |e: String| ("java/lang/IllegalArgumentException", e);
        let matcher = FaceMatcher::new(&bytes(&mut env, &yunet)?, &bytes(&mut env, &sface)?).map_err(|e| state(e.to_string()))?;
        let photo = decode_photo(&bytes(&mut env, &chip_photo)?).map_err(invalid)?;
        let face = *matcher.detect(&photo).map_err(|e| state(e.to_string()))?.first().ok_or_else(|| invalid("no face in the chip photo".into()))?;
        let chip = matcher.embed(&photo, &face).map_err(|e| state(e.to_string()))?;
        Ok(Session { matcher, chip, check: LivenessCheck::default() })
    })();
    match result {
        Ok(session) => Box::into_raw(Box::new(session)) as jlong,
        Err(e) => throw(&mut env, e, 0),
    }
}

/// Checks one camera frame, RGBA as CameraX delivers it, turned upright by `rotation`
/// degrees clockwise. Returns `[face found (0 or 1), similarity to the chip photo, liveness
/// state (0 center, 1 turns, 2 done, 3 confirm), center or confirm frames, turns done as bits in
/// [Turn] order, yaw,
/// pitch]`.
///
/// [Turn]: facematch::Turn
#[no_mangle]
pub extern "system" fn Java_cl_zkage_app_NativeFace_frame<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    rgba: JByteArray<'local>,
    width: jint,
    height: jint,
    rotation: jint,
) -> jfloatArray {
    // SAFETY: `handle` comes from `open` and Kotlin stops calling `frame` before `close`.
    let session = unsafe { &mut *(handle as *mut Session) };
    let result = (|| -> JniResult<Vec<f32>> {
        let state = |e: String| ("java/lang/IllegalStateException", e);
        let raw = bytes(&mut env, &rgba)?;
        let rgba = RgbaImage::from_raw(width as u32, height as u32, raw).ok_or_else(|| state("frame size mismatch".into()))?;
        let rgb: RgbImage = image::DynamicImage::ImageRgba8(rgba).to_rgb8();
        let upright = match rotation {
            90 => imageops::rotate90(&rgb),
            180 => imageops::rotate180(&rgb),
            270 => imageops::rotate270(&rgb),
            _ => rgb,
        };
        let Some(face) = session.matcher.detect(&upright).map_err(|e| state(e.to_string()))?.first().copied() else {
            return Ok(encode(false, 0.0, session.check.lost(), None));
        };
        let similarity = cosine(&session.chip, &session.matcher.embed(&upright, &face).map_err(|e| state(e.to_string()))?);
        let pose = pose(&face);
        Ok(encode(true, similarity, session.check.feed(pose, similarity), Some(pose)))
    })();
    match result.and_then(|out| {
        let array = env.new_float_array(out.len() as i32).map_err(|e| ("java/lang/IllegalStateException", e.to_string()))?;
        env.set_float_array_region(&array, 0, &out).map_err(|e| ("java/lang/IllegalStateException", e.to_string()))?;
        Ok(array)
    }) {
        Ok(array) => array.into_raw(),
        Err(e) => throw(&mut env, e, std::ptr::null_mut()),
    }
}

fn encode(found: bool, similarity: f32, liveness: Liveness, pose: Option<Pose>) -> Vec<f32> {
    let (state, center, turns) = match liveness {
        Liveness::Center(n) => (0, n, 0),
        Liveness::Turns(done) => (1, 0, done.iter().enumerate().map(|(i, &d)| (d as u8) << i).sum()),
        Liveness::Confirm(n) => (3, n, 0b11),
        Liveness::Done => (2, 0, 0b11),
    };
    let (yaw, pitch) = pose.map_or((0.0, 0.0), |p| (p.yaw, p.pitch));
    vec![found as u8 as f32, similarity, state as f32, f32::from(center), f32::from(turns), yaw, pitch]
}

/// Frees the session, with the chip photo's embedding.
#[no_mangle]
pub extern "system" fn Java_cl_zkage_app_NativeFace_close<'local>(_env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong) {
    if handle != 0 {
        // SAFETY: `handle` comes from `open` and is closed once.
        drop(unsafe { Box::from_raw(handle as *mut Session) });
    }
}
