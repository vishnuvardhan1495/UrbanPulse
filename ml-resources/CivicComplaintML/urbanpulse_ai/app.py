import os
import io
import json
import logging
import numpy as np
import tensorflow as tf
from flask import Flask, request, jsonify
from PIL import Image

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("urbanpulse-ai")

app = Flask(__name__)

# Base directory for robust relative resolution
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MODELS_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "models"))

# Model configuration paths (Single-stage 8-class classification model)
CLASSIFIER_MODEL_PATH = os.environ.get(
    "CLASSIFIER_MODEL_PATH",
    os.path.join(DEFAULT_MODELS_DIR, "urbanpulse_8class_pothole_stage1.keras")
)
CLASSES_PATH = os.environ.get(
    "CLASSES_PATH",
    os.path.join(DEFAULT_MODELS_DIR, "urbanpulse_8class_class_names.json")
)
MODEL_VERSION = os.environ.get("MODEL_VERSION", "urbanpulse_8class_pothole_stage1")

logger.info(f"Loading 8-Class Classifier from: {CLASSIFIER_MODEL_PATH}")
logger.info(f"Loading Class Names from: {CLASSES_PATH}")

# Load Eight-Class Classifier
try:
    classifier_model = tf.keras.models.load_model(
        CLASSIFIER_MODEL_PATH,
        compile=False
    )
    logger.info("8-Class Classifier model loaded successfully!")
except Exception as e:
    logger.error(f"Failed to load 8-Class Classifier: {e}")
    classifier_model = None

# Model alias for backwards compatibility
model = classifier_model

# Load 8 Class Names
try:
    with open(CLASSES_PATH, "r", encoding="utf-8") as f:
        class_names = json.load(f)
    logger.info(f"Loaded {len(class_names)} classes: {class_names}")
except Exception as e:
    logger.error(f"Failed to load class names: {e}")
    class_names = []


@app.route("/health", methods=["GET"])
def health():
    classifier_ok = classifier_model is not None

    if not classifier_ok:
        return jsonify({
            "status": "error",
            "model_loaded": False,
            "classifier_model_loaded": False,
            "error": "8-class classification model failed to load"
        }), 503

    return jsonify({
        "status": "ok",
        "pipeline": "single-stage-8class",
        "model_loaded": True,
        "classifier_model_loaded": True,
        "classifier_model_name": os.path.basename(CLASSIFIER_MODEL_PATH),
        "model_version": MODEL_VERSION,
        "num_classes": len(class_names),
        "classes": class_names,
        "input_dimensions": [224, 224, 3],
        "input_shape": [None, 224, 224, 3]
    }), 200


@app.route("/predict", methods=["POST"])
def predict():
    if classifier_model is None:
        return jsonify({"error": "Classification model is not loaded in memory"}), 503

    # Support 'file' (Spring Boot default) with 'image' fallback
    uploaded = request.files.get("file")
    if uploaded is None or uploaded.filename == "":
        uploaded = request.files.get("image")

    if uploaded is None or uploaded.filename == "":
        return jsonify({"error": "Upload an image using the 'file' multipart field"}), 400

    try:
        file_bytes = uploaded.read()
        if not file_bytes:
            return jsonify({"error": "Uploaded file is empty"}), 400

        # Validate and open image
        try:
            image = Image.open(io.BytesIO(file_bytes)).convert("RGB")
        except Exception as img_err:
            return jsonify({"error": f"Invalid or unreadable image: {str(img_err)}"}), 400

        image = image.resize((224, 224))
        array = np.asarray(image, dtype=np.float32)
        array = np.expand_dims(array, axis=0)

        # Execute 8-Class Classifier directly
        predictions = classifier_model.predict(array, verbose=0)[0]
        index = int(np.argmax(predictions))
        confidence = float(predictions[index])
        predicted_class = class_names[index] if index < len(class_names) else f"Class_{index}"

        probabilities = [float(p) for p in predictions]
        class_probabilities = {
            name: float(score)
            for name, score in zip(class_names, predictions)
        }

        logger.info(
            f"8-Class Prediction: {predicted_class} (Confidence: {confidence:.4f})"
        )

        return jsonify({
            "success": True,
            "predictedClass": predicted_class,
            "confidence": confidence,
            "probabilities": probabilities,
            "classProbabilities": class_probabilities,
            "modelVersion": MODEL_VERSION
        }), 200

    except Exception as exc:
        logger.error(f"Error during 8-class classification: {exc}", exc_info=True)
        return jsonify({"error": f"Invalid or corrupted image: {str(exc)}"}), 400


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5001))
    logger.info(f"Starting Urban Pulse 8-Class ML Service on port {port}...")
    app.run(host="0.0.0.0", port=port)
