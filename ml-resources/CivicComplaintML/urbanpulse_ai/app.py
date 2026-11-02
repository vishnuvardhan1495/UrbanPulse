import os
import json
import logging
import numpy as np
import tensorflow as tf
from flask import Flask, request, jsonify
from PIL import Image
from io import BytesIO

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("urbanpulse-ai")

app = Flask(__name__)

# Base directory for relative resolution
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MODELS_DIR = os.path.join(os.path.dirname(BASE_DIR), "models")

MODEL_PATH = os.environ.get(
    "MODEL_PATH",
    os.path.join(DEFAULT_MODELS_DIR, "urbanpulse_8class_pothole_stage1.keras")
)
CLASSES_PATH = os.environ.get(
    "CLASSES_PATH",
    os.path.join(DEFAULT_MODELS_DIR, "urbanpulse_8class_class_names.json")
)
MODEL_VERSION = os.environ.get("MODEL_VERSION", "urbanpulse_8class_pothole_stage1")

logger.info(f"Loading model from: {MODEL_PATH}")
logger.info(f"Loading classes from: {CLASSES_PATH}")

try:
    model = tf.keras.models.load_model(MODEL_PATH, compile=False)
    logger.info("Keras model loaded successfully!")
except Exception as e:
    logger.error(f"Failed to load model: {e}")
    model = None

try:
    with open(CLASSES_PATH, "r", encoding="utf-8") as f:
        class_names = json.load(f)
    logger.info(f"Loaded {len(class_names)} classes: {class_names}")
except Exception as e:
    logger.error(f"Failed to load class names: {e}")
    class_names = []

@app.get("/health")
def health():
    if model is None:
        return jsonify({
            "status": "error",
            "model_loaded": False,
            "error": "Model failed to load"
        }), 503

    return jsonify({
        "status": "ok",
        "model_loaded": True,
        "model_name": os.path.basename(MODEL_PATH),
        "model_version": MODEL_VERSION,
        "classes": class_names,
        "num_classes": len(class_names)
    })

@app.post("/predict")
def predict():
    if model is None:
        return jsonify({"error": "Model is not loaded"}), 503

    uploaded = request.files.get("file")
    if uploaded is None or uploaded.filename == "":
        return jsonify({"error": "Upload an image using the 'file' multipart field"}), 400

    try:
        file_bytes = uploaded.read()
        if not file_bytes:
            return jsonify({"error": "Uploaded file is empty"}), 400

        image = Image.open(BytesIO(file_bytes)).convert("RGB")
        image = image.resize((224, 224))
        # Note: urbanpulse_8class_pothole_stage1.keras has internal TrueDivide and Subtract layers
        # for MobileNetV2 normalization. Do not apply extra normalization. Input is float32 [0, 255].
        array = np.asarray(image, dtype=np.float32)
        array = np.expand_dims(array, axis=0)

        predictions = model.predict(array, verbose=0)[0]
        index = int(np.argmax(predictions))
        confidence = float(predictions[index])

        probabilities = {
            name: float(score)
            for name, score in zip(class_names, predictions)
        }

        predicted_class = class_names[index]
        logger.info(f"Prediction: {predicted_class} (Confidence: {confidence:.4f})")

        return jsonify({
            "predictedClass": predicted_class,
            "confidence": confidence,
            "probabilities": probabilities,
            "modelVersion": MODEL_VERSION
        })
    except Exception as exc:
        logger.error(f"Error during classification: {exc}")
        return jsonify({"error": f"Could not classify image: {str(exc)}"}), 400

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5001))
    app.run(host="0.0.0.0", port=port)
