//! On-device face match between the chip photo (DG2) and the camera, for decision 0007.
//!
//! A local check for honest users: its result is not in the proof and promises nothing to a
//! verifier, since the camera image is an input nobody signs.
//!
//! Detection is OpenCV Zoo's YuNet (MIT) and recognition its SFace (Apache-2.0). The
//! pre- and post-processing follow OpenCV's `FaceDetectorYN` and `FaceRecognizerSF`
//! (modules/objdetect/src/face_detect.cpp and face_recognize.cpp). The 5-point alignment and
//! the head pose are adapted from ZKPassport's mobile-app (Apache-2.0, commit c52f5ef,
//! modules/facematch/rust/src/facematch.rs and pose.rs). Changes: the SVD-based Umeyama fit is
//! replaced by the closed-form 2D similarity, which gives the same transform for 2D points
//! without a reflection, and the SCRFD/ArcFace models by YuNet/SFace.

use image::{imageops, RgbImage};
use tract_onnx::prelude::*;

/// SFace's cosine threshold for "same person" (OpenCV Zoo, sface.py).
pub const SAME_PERSON_COSINE: f32 = 0.363;

const DETECT_SIZE: u32 = 640;
const STRIDES: [usize; 3] = [8, 16, 32];
const SCORE_THRESHOLD: f32 = 0.6;
const NMS_THRESHOLD: f32 = 0.3;
const ALIGNED: u32 = 112;

/// Where SFace expects the eyes, nose tip and mouth corners in its 112×112 input.
const REFERENCE: [[f32; 2]; 5] = [
    [38.2946, 51.6963],
    [73.5318, 51.5014],
    [56.0252, 71.7366],
    [41.5493, 92.3655],
    [70.7299, 92.2041],
];

type Model = Arc<TypedSimplePlan>;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Face {
    /// x, y, width, height in the image's pixels.
    pub bbox: [f32; 4],
    /// The image-left eye, image-right eye, nose tip, image-left and image-right mouth corners.
    pub landmarks: [[f32; 2]; 5],
    pub score: f32,
}

/// Head pose from the landmarks, as ZKPassport computes it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pose {
    /// -1 to 1: positive when the nose is nearer the image-left eye, so the head turned
    /// toward the image's left.
    pub yaw: f32,
    pub roll_deg: f32,
    /// Unitless; grows when the nose moves toward the eyes, so the head tilted up.
    pub pitch: f32,
}

pub struct FaceMatcher {
    detector: Model,
    recognizer: Model,
}

impl FaceMatcher {
    pub fn new(yunet_onnx: &[u8], sface_onnx: &[u8]) -> TractResult<Self> {
        let load = |bytes: &[u8]| -> TractResult<Model> {
            tract_onnx::onnx().model_for_read(&mut &*bytes)?.into_optimized()?.into_runnable()
        };
        Ok(Self { detector: load(yunet_onnx)?, recognizer: load(sface_onnx)? })
    }

    /// The faces in the image, best first.
    pub fn detect(&self, image: &RgbImage) -> TractResult<Vec<Face>> {
        // Scale to fit 640×640 and pad the bottom and right with black, as a letterbox.
        let scale = DETECT_SIZE as f32 / image.width().max(image.height()) as f32;
        let (w, h) = ((image.width() as f32 * scale).round() as u32, (image.height() as f32 * scale).round() as u32);
        let resized = imageops::resize(image, w.max(1), h.max(1), imageops::FilterType::Triangle);
        let size = DETECT_SIZE as usize;
        // OpenCV's blobFromImage without swapRB: BGR, 0 to 255.
        let input = tract_ndarray::Array4::from_shape_fn((1, 3, size, size), |(_, c, y, x)| {
            if x < w as usize && y < h as usize {
                f32::from(resized.get_pixel(x as u32, y as u32)[2 - c])
            } else {
                0.0
            }
        });
        let out = self.detector.run(tvec!(Tensor::from(input).into()))?;

        let mut faces = Vec::new();
        for (i, stride) in STRIDES.iter().enumerate() {
            let cls = out[i].try_as_plain_ram()?.as_slice::<f32>()?;
            let obj = out[i + 3].try_as_plain_ram()?.as_slice::<f32>()?;
            let bbox = out[i + 6].try_as_plain_ram()?.as_slice::<f32>()?;
            let kps = out[i + 9].try_as_plain_ram()?.as_slice::<f32>()?;
            let cols = size / stride;
            for idx in 0..cls.len() {
                let score = (cls[idx].clamp(0.0, 1.0) * obj[idx].clamp(0.0, 1.0)).sqrt();
                if score < SCORE_THRESHOLD {
                    continue;
                }
                let (r, c) = ((idx / cols) as f32, (idx % cols) as f32);
                let s = *stride as f32;
                let cx = (c + bbox[idx * 4]) * s;
                let cy = (r + bbox[idx * 4 + 1]) * s;
                let bw = bbox[idx * 4 + 2].exp() * s;
                let bh = bbox[idx * 4 + 3].exp() * s;
                let mut landmarks = [[0.0; 2]; 5];
                for (n, point) in landmarks.iter_mut().enumerate() {
                    *point = [(kps[idx * 10 + 2 * n] + c) * s / scale, (kps[idx * 10 + 2 * n + 1] + r) * s / scale];
                }
                faces.push(Face {
                    bbox: [(cx - bw / 2.0) / scale, (cy - bh / 2.0) / scale, bw / scale, bh / scale],
                    landmarks,
                    score,
                });
            }
        }
        Ok(nms(faces))
    }

    /// SFace's 128-value embedding of the face, normalized to length 1.
    pub fn embed(&self, image: &RgbImage, face: &Face) -> TractResult<Vec<f32>> {
        let aligned = align(image, &face.landmarks);
        let size = ALIGNED as usize;
        // OpenCV's blobFromImage with swapRB on a BGR image: RGB, 0 to 255.
        let input = tract_ndarray::Array4::from_shape_fn((1, 3, size, size), |(_, c, y, x)| {
            f32::from(aligned.get_pixel(x as u32, y as u32)[c])
        });
        let out = self.recognizer.run(tvec!(Tensor::from(input).into()))?;
        let v = out[0].try_as_plain_ram()?.as_slice::<f32>()?;
        let norm = v.iter().map(|x| x * x).sum::<f32>().sqrt().max(1e-10);
        Ok(v.iter().map(|x| x / norm).collect())
    }
}

/// Decodes the photo in DG2's image data: JPEG or JPEG 2000 (a JP2 file or a bare codestream),
/// the two formats ICAO 9303 allows.
pub fn decode_photo(bytes: &[u8]) -> Result<RgbImage, String> {
    const JP2: &[u8] = &[0, 0, 0, 0x0C, b'j', b'P', b' ', b' '];
    const J2K: &[u8] = &[0xFF, 0x4F, 0xFF, 0x51];
    if bytes.starts_with(JP2) || bytes.starts_with(J2K) {
        let jp2 = jpeg2k::Image::from_bytes(bytes).map_err(|e| format!("JPEG 2000: {e}"))?;
        let image = image::DynamicImage::try_from(&jp2).map_err(|e| format!("JPEG 2000: {e}"))?;
        Ok(image.to_rgb8())
    } else {
        Ok(image::load_from_memory(bytes).map_err(|e| format!("JPEG: {e}"))?.to_rgb8())
    }
}

/// Cosine similarity of two normalized embeddings.
pub fn cosine(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

pub fn pose(face: &Face) -> Pose {
    let [l, r, n, ml, mr] = face.landmarks;
    let dist = |a: [f32; 2], b: [f32; 2]| ((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2)).sqrt();
    let roll_deg = (r[1] - l[1]).atan2(r[0] - l[0]).to_degrees();
    let (d_nr, d_nl) = (dist(n, r), dist(n, l));
    let yaw = (d_nr - d_nl) / (d_nr + d_nl + 1e-6);
    let eyes_y = (l[1] + r[1]) * 0.5;
    let mouth_y = (ml[1] + mr[1]) * 0.5;
    // ZKPassport divides by the distance between the eyes, which shrinks as the head turns
    // sideways and made a turn read as a tilt up on the S25. The eyes-to-mouth height barely
    // changes with a turn. At the SFace reference points this scale is 35.2 / 40.7 = 0.865
    // times ZKPassport's.
    let height = (mouth_y - eyes_y).abs().max(1e-6);
    let pitch = ((eyes_y - n[1]) - (n[1] - mouth_y)) / height;
    Pose { yaw, roll_deg, pitch }
}

/// A head turn to one side, named by where the nose moves in the camera's unmirrored image.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Turn {
    ImageRight,
    ImageLeft,
}

/// The turns in the order they are asked: the person's own right, then their left. The image
/// is not mirrored, so the person's right is the image's left.
pub const TURNS: [Turn; 2] = [Turn::ImageLeft, Turn::ImageRight];

/// What the liveness check still needs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Liveness {
    /// Look straight at the camera; the frames so far, out of [CENTER_FRAMES].
    Center(u8),
    /// Turn the head to the side `TURNS[i]`; the turns before it are done.
    Turn(usize),
    /// Look straight at the camera again; the frames so far, out of [CENTER_FRAMES].
    Confirm(u8),
    Done,
}

/// Frames looking straight ahead needed first and last; the first ones also set the person's
/// neutral pose.
pub const CENTER_FRAMES: u8 = 2;
/// Frames needed on each side.
const TURN_FRAMES: u8 = 3;
/// How far the head must turn sideways from its neutral pose. Measured on a Galaxy S25 with
/// YuNet's landmarks (2026-10-29): a clear turn reaches a yaw of 0.17 to 0.26, while a still
/// head wanders up to about 0.05. ZKPassport's 0.23 was too high for these landmarks.
const TURN_YAW: f32 = 0.15;
/// How far from straight ahead a center frame may be.
const CENTER_YAW: f32 = 0.1;
/// Similarity a turned face must keep. A turned face scores lower against the frontal chip
/// photo, so the full [SAME_PERSON_COSINE] is asked only of the center frames, before and after
/// the turns. Two different people scored 0.10 and 0.15 on the test portraits; turned to the
/// side, the S25 user scored 0.55 to 0.71.
const TURN_SIMILARITY: f32 = 0.15;
/// Frames without a face that restart the turns: about a second at the S25's rate.
const MAX_LOST_FRAMES: u8 = 10;

/// Liveness against a still photo: the person looks ahead, turns the head to their right, then
/// to their left ([TURNS]), and looks ahead again. A turn to the wrong side does not count. A flat photo cannot turn. The face must match the chip photo
/// at the start and the end, stay similar while turning, and not leave the frame for long. It
/// does not stop a video or a mask.
///
/// Only sideways turns: ZKPassport also asks for up and down, but those add nothing against a
/// photo, and looking up dropped the similarity to the frontal chip photo to 0.18–0.28 on the
/// S25, so the check rarely finished.
///
/// The turn is measured from the person's own neutral pose, taken from the first center frames.
#[derive(Clone, Debug, Default)]
pub struct LivenessCheck {
    center: Vec<Pose>,
    turns: [u8; 2],
    confirm: u8,
    lost: u8,
}

impl LivenessCheck {
    /// Feeds one camera frame's pose and its similarity to the chip photo.
    pub fn feed(&mut self, pose: Pose, similarity: f32) -> Liveness {
        self.lost = 0;
        let same = similarity >= SAME_PERSON_COSINE;
        match self.state() {
            Liveness::Center(_) => {
                // Before calibrating, "straight ahead" is judged from zero yaw and roll.
                if same && pose.yaw.abs() < CENTER_YAW && pose.roll_deg.abs() < 15.0 {
                    self.center.push(pose);
                }
            }
            Liveness::Turn(i) => {
                if similarity >= TURN_SIMILARITY && self.turn(pose) == Some(TURNS[i]) {
                    self.turns[i] += 1;
                }
            }
            Liveness::Confirm(_) => {
                if same && self.turn(pose).is_none() {
                    self.confirm += 1;
                }
            }
            Liveness::Done => {}
        }
        self.state()
    }

    /// A frame without a face. Losing it for long while turning restarts the turns, so a face
    /// cannot be swapped out of view.
    pub fn lost(&mut self) -> Liveness {
        self.lost = self.lost.saturating_add(1);
        if self.lost > MAX_LOST_FRAMES && matches!(self.state(), Liveness::Turn(_) | Liveness::Confirm(_)) {
            self.turns = [0; 2];
            self.confirm = 0;
        }
        self.state()
    }

    pub fn state(&self) -> Liveness {
        if self.neutral().is_none() {
            Liveness::Center(self.center.len() as u8)
        } else if let Some(i) = self.turns.iter().position(|&n| n < TURN_FRAMES) {
            Liveness::Turn(i)
        } else if self.confirm < CENTER_FRAMES {
            Liveness::Confirm(self.confirm)
        } else {
            Liveness::Done
        }
    }

    /// How far the head is turned toward the side asked now, as a fraction of a full turn: 1 or
    /// more is far enough, below 0 is the wrong side. None outside the turns. For the screen only.
    pub fn reach(&self, pose: Pose) -> Option<f32> {
        let Liveness::Turn(i) = self.state() else { return None };
        let dx = pose.yaw - self.neutral()?.yaw;
        Some(match TURNS[i] {
            Turn::ImageLeft => dx,
            Turn::ImageRight => -dx,
        } / TURN_YAW)
    }

    /// The side the head is turned to, if far enough from neutral.
    fn turn(&self, pose: Pose) -> Option<Turn> {
        let neutral = self.neutral()?;
        // Positive yaw: the nose moved toward the image's left.
        let dx = pose.yaw - neutral.yaw;
        if dx >= TURN_YAW {
            Some(Turn::ImageLeft)
        } else if dx <= -TURN_YAW {
            Some(Turn::ImageRight)
        } else {
            None
        }
    }

    fn neutral(&self) -> Option<Pose> {
        (self.center.len() >= CENTER_FRAMES as usize).then(|| {
            let n = self.center.len() as f32;
            let sum = |f: fn(&Pose) -> f32| self.center.iter().map(f).sum::<f32>() / n;
            Pose { yaw: sum(|p| p.yaw), roll_deg: sum(|p| p.roll_deg), pitch: sum(|p| p.pitch) }
        })
    }
}

/// Greedy non-maximum suppression by box overlap, best score first.
fn nms(mut faces: Vec<Face>) -> Vec<Face> {
    faces.sort_by(|a, b| b.score.total_cmp(&a.score));
    let mut keep: Vec<Face> = Vec::new();
    for face in faces {
        if keep.iter().all(|k| iou(&k.bbox, &face.bbox) <= NMS_THRESHOLD) {
            keep.push(face);
        }
    }
    keep
}

fn iou(a: &[f32; 4], b: &[f32; 4]) -> f32 {
    let w = (a[0] + a[2]).min(b[0] + b[2]) - a[0].max(b[0]);
    let h = (a[1] + a[3]).min(b[1] + b[3]) - a[1].max(b[1]);
    let inter = w.max(0.0) * h.max(0.0);
    inter / (a[2] * a[3] + b[2] * b[3] - inter).max(1e-6)
}

/// The similarity transform (rotation, uniform scale, translation) that best maps `src` onto
/// `dst` by least squares, as [a, -b, tx; b, a, ty].
fn similarity(src: &[[f32; 2]; 5], dst: &[[f32; 2]; 5]) -> [f32; 6] {
    let mean = |p: &[[f32; 2]; 5]| {
        let (x, y) = p.iter().fold((0.0, 0.0), |(x, y), q| (x + q[0], y + q[1]));
        [x / 5.0, y / 5.0]
    };
    let (ms, md) = (mean(src), mean(dst));
    let (mut num_a, mut num_b, mut den) = (0.0, 0.0, 0.0);
    for (s, d) in src.iter().zip(dst) {
        let (sx, sy, dx, dy) = (s[0] - ms[0], s[1] - ms[1], d[0] - md[0], d[1] - md[1]);
        num_a += sx * dx + sy * dy;
        num_b += sx * dy - sy * dx;
        den += sx * sx + sy * sy;
    }
    let (a, b) = (num_a / den.max(1e-6), num_b / den.max(1e-6));
    [a, -b, md[0] - (a * ms[0] - b * ms[1]), b, a, md[1] - (b * ms[0] + a * ms[1])]
}

/// The face warped to SFace's 112×112 input, sampling bilinearly.
fn align(image: &RgbImage, landmarks: &[[f32; 2]; 5]) -> RgbImage {
    let [a, b, c, d, e, f] = similarity(landmarks, &REFERENCE);
    let det = a * e - b * d;
    let (ia, ib, id, ie) = (e / det, -b / det, -d / det, a / det);
    let (ic, if_) = (-(ia * c + ib * f), -(id * c + ie * f));
    let (w, h) = (image.width() as i64, image.height() as i64);
    let px = |x: i64, y: i64, ch: usize| {
        if x < 0 || y < 0 || x >= w || y >= h {
            0.0
        } else {
            f32::from(image.get_pixel(x as u32, y as u32)[ch])
        }
    };
    RgbImage::from_fn(ALIGNED, ALIGNED, |x, y| {
        let sx = ia * x as f32 + ib * y as f32 + ic;
        let sy = id * x as f32 + ie * y as f32 + if_;
        let (x0, y0) = (sx.floor() as i64, sy.floor() as i64);
        let (fx, fy) = (sx - x0 as f32, sy - y0 as f32);
        image::Rgb(std::array::from_fn(|ch| {
            let top = px(x0, y0, ch) * (1.0 - fx) + px(x0 + 1, y0, ch) * fx;
            let bottom = px(x0, y0 + 1, ch) * (1.0 - fx) + px(x0 + 1, y0 + 1, ch) * fx;
            (top * (1.0 - fy) + bottom * fy).round().clamp(0.0, 255.0) as u8
        }))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn similarity_recovers_a_known_transform() {
        // Rotate 30°, scale by 1.5 and shift: the fit must give back exactly that.
        let (s, t) = (1.5f32, 30f32.to_radians());
        let (a, b) = (s * t.cos(), s * t.sin());
        let dst = REFERENCE.map(|[x, y]| [a * x - b * y + 7.0, b * x + a * y - 3.0]);
        let m = similarity(&REFERENCE, &dst);
        for (got, want) in m.iter().zip([a, -b, 7.0, b, a, -3.0]) {
            assert!((got - want).abs() < 1e-3, "{m:?}");
        }
    }

    fn at(yaw: f32, pitch: f32) -> Pose {
        Pose { yaw, roll_deg: 0.0, pitch }
    }

    /// Feeds `n` frames of the same pose.
    fn hold(check: &mut LivenessCheck, pose: Pose, similarity: f32, n: u8) -> Liveness {
        (0..n).map(|_| check.feed(pose, similarity)).last().unwrap()
    }

    #[test]
    fn liveness_needs_center_both_sides_and_center_again() {
        let mut check = LivenessCheck::default();
        let same = 0.6;
        assert_eq!(check.feed(at(0.0, -0.16), same), Liveness::Center(1));
        assert_eq!(check.feed(at(0.02, -0.16), same), Liveness::Turn(0));
        // Small movements and nods do not count.
        assert_eq!(hold(&mut check, at(0.1, -0.16), same, 5), Liveness::Turn(0));
        assert_eq!(hold(&mut check, at(0.0, 0.3), same, 5), Liveness::Turn(0));
        assert_eq!(hold(&mut check, at(0.2, -0.16), 0.3, TURN_FRAMES), Liveness::Turn(1));
        assert_eq!(hold(&mut check, at(-0.2, -0.16), same, TURN_FRAMES), Liveness::Confirm(0));
        // The last frames must be frontal and fully match again.
        assert_eq!(check.feed(at(0.0, -0.16), 0.3), Liveness::Confirm(0));
        assert_eq!(check.feed(at(0.2, -0.16), same), Liveness::Confirm(0));
        assert_eq!(hold(&mut check, at(0.0, -0.16), same, CENTER_FRAMES), Liveness::Done);
    }

    #[test]
    fn liveness_ignores_frames_of_someone_else() {
        let mut check = LivenessCheck::default();
        assert_eq!(hold(&mut check, at(0.0, -0.16), 0.2, 10), Liveness::Center(0));
        hold(&mut check, at(0.0, -0.16), 0.6, CENTER_FRAMES);
        assert_eq!(hold(&mut check, at(0.4, -0.16), 0.1, 10), Liveness::Turn(0));
    }

    #[test]
    fn losing_the_face_restarts_the_turns() {
        let mut check = LivenessCheck::default();
        hold(&mut check, at(0.0, 0.0), 0.6, CENTER_FRAMES);
        hold(&mut check, at(0.2, 0.0), 0.6, TURN_FRAMES);
        assert_eq!(check.state(), Liveness::Turn(1));
        for _ in 0..MAX_LOST_FRAMES {
            check.lost();
        }
        assert_eq!(check.state(), Liveness::Turn(1));
        assert_eq!(check.lost(), Liveness::Turn(0));
    }

    #[test]
    fn turns_go_right_then_left() {
        let mut check = LivenessCheck::default();
        hold(&mut check, at(0.0, 0.0), 0.6, CENTER_FRAMES);
        // Negative yaw: the nose is nearer the image-right eye, a turn to the person's left.
        // Asked first is the right, so the left does not count yet.
        assert_eq!(hold(&mut check, at(-0.2, 0.0), 0.6, 10), Liveness::Turn(0));
        assert_eq!(hold(&mut check, at(0.2, 0.0), 0.6, TURN_FRAMES), Liveness::Turn(1));
        // Turning right again does not do the left.
        assert_eq!(hold(&mut check, at(0.2, 0.0), 0.6, 10), Liveness::Turn(1));
        assert_eq!(hold(&mut check, at(-0.2, 0.0), 0.6, TURN_FRAMES), Liveness::Confirm(0));
    }

    #[test]
    fn reach_is_toward_the_side_asked() {
        let mut check = LivenessCheck::default();
        assert_eq!(check.reach(at(0.1, 0.0)), None);
        hold(&mut check, at(0.0, 0.0), 0.6, CENTER_FRAMES);
        let reach = |check: &LivenessCheck, yaw| check.reach(at(yaw, 0.0)).unwrap();
        assert!((reach(&check, TURN_YAW / 2.0) - 0.5).abs() < 1e-6);
        assert!((reach(&check, -TURN_YAW) + 1.0).abs() < 1e-6);
        hold(&mut check, at(0.2, 0.0), 0.6, TURN_FRAMES);
        assert!((reach(&check, -TURN_YAW) - 1.0).abs() < 1e-6);
    }

    /// A sideways turn foreshortens the face horizontally; that alone must not read as a tilt.
    #[test]
    fn pitch_ignores_a_sideways_squeeze() {
        let level = pose(&Face { bbox: [0.0; 4], landmarks: REFERENCE, score: 1.0 });
        let squeezed = REFERENCE.map(|[x, y]| [56.0 + (x - 56.0) * 0.6, y]);
        let turned = pose(&Face { bbox: [0.0; 4], landmarks: squeezed, score: 1.0 });
        assert!((level.pitch - turned.pitch).abs() < 1e-4, "{level:?} {turned:?}");
    }

    #[test]
    fn pose_is_level_for_reference_points() {
        let p = pose(&Face { bbox: [0.0; 4], landmarks: REFERENCE, score: 1.0 });
        assert!(p.yaw.abs() < 0.01 && p.roll_deg.abs() < 0.5, "{p:?}");
    }
}
