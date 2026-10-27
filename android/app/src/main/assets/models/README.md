# Bundled person-detection model

EfficientDet-Lite0, uint8 metadata model, TensorFlow Authors, Apache License 2.0 (included in LICENSE).

Source: https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite

SHA-256: `2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b`.

The build verifies this hash. The asset is uncompressed for the standard TensorFlow Lite Task Vision 0.4.4 object detector. Embedded metadata supplies the COCO labels (including person). No runtime model download or external inference service is used.

Detection is owner-enabled and sampled every two seconds while armed. Scores below 0.6 are omitted. Two positive observations establish presence; three negative observations clear it. Event cooldown is 30 seconds per camera. No face embeddings, unique-identity claims or cross-camera tracking are implemented. Device inference accuracy, performance, thermal behavior and native-library/OS compatibility require the physical-device gates in docs/device-acceptance.md.
