#!/bin/sh
# Downloads the face models for decision 0007 into facematch/models (git-ignored), pinned by
# Hugging Face revision and checked by SHA-256.
#
#     prover/scripts/download-face-models.sh
#
# YuNet (MIT) and SFace (Apache-2.0) from OpenCV Zoo. Their licenses are saved next to them.
set -eu
dest=$(cd "$(dirname "$0")/.." && pwd)/facematch/models
mkdir -p "$dest"

fetch() { # repo revision file sha256
    out="$dest/$3"
    if [ ! -f "$out" ] || ! echo "$4  $out" | shasum -a 256 -c - >/dev/null 2>&1; then
        curl -fsSL --retry 5 --retry-all-errors -o "$out" "https://huggingface.co/opencv/$1/resolve/$2/$3"
    fi
    echo "$4  $out" | shasum -a 256 -c -
}

fetch face_detection_yunet 3cc26e7f1014a5ee5d74a42acee58bafc9d0a310 face_detection_yunet_2023mar.onnx \
    8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
fetch face_recognition_sface 3d7082438a6e4551e840c9b2bb60b71e8da4b524 face_recognition_sface_2021dec.onnx \
    0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79
curl -fsSL --retry 5 --retry-all-errors -o "$dest/LICENSE-yunet" \
    https://huggingface.co/opencv/face_detection_yunet/resolve/3cc26e7f1014a5ee5d74a42acee58bafc9d0a310/LICENSE
curl -fsSL --retry 5 --retry-all-errors -o "$dest/LICENSE-sface" \
    https://huggingface.co/opencv/face_recognition_sface/resolve/3d7082438a6e4551e840c9b2bb60b71e8da4b524/LICENSE
