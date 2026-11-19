import os
import sys
import io
import unittest
import numpy as np
from PIL import Image

# Add Flask app directory to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "urbanpulse_ai")))
import app

class SingleStage8ClassPipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        app.app.testing = True
        cls.client = app.app.test_client()

    def create_test_image_bytes(self, color=(128, 128, 128), size=(224, 224)):
        img = Image.new("RGB", size, color=color)
        bio = io.BytesIO()
        img.save(bio, format="JPEG")
        bio.seek(0)
        return bio

    def test_01_health_endpoint(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertEqual(data.get("status"), "ok")
        self.assertEqual(data.get("pipeline"), "single-stage-8class")
        self.assertTrue(data.get("classifier_model_loaded"))
        self.assertEqual(data.get("num_classes"), 8)
        self.assertEqual(len(data.get("classes", [])), 8)
        print("[TEST 01 PASSED] Health endpoint reports 8-class classifier healthy.")

    def test_02_empty_file_returns_400(self):
        data = {"file": (io.BytesIO(b""), "empty.jpg")}
        response = self.client.post("/predict", data=data, content_type="multipart/form-data")
        self.assertEqual(response.status_code, 400)
        json_data = response.get_json()
        self.assertIn("error", json_data)
        print("[TEST 02 PASSED] Empty file rejected with HTTP 400.")

    def test_03_no_file_field_returns_400(self):
        response = self.client.post("/predict", data={}, content_type="multipart/form-data")
        self.assertEqual(response.status_code, 400)
        json_data = response.get_json()
        self.assertIn("error", json_data)
        print("[TEST 03 PASSED] Missing file field returns HTTP 400.")

    def test_04_corrupted_image_returns_400(self):
        corrupted_bytes = io.BytesIO(b"NotAJpegOrPngImageFileHeaderGarbageContent1234567890")
        response = self.client.post(
            "/predict",
            data={"file": (corrupted_bytes, "corrupt.jpg")},
            content_type="multipart/form-data"
        )
        self.assertEqual(response.status_code, 400)
        json_data = response.get_json()
        self.assertIn("error", json_data)
        print("[TEST 04 PASSED] Corrupted image rejected with HTTP 400.")

    def test_05_predict_format_and_probabilities(self):
        img_bytes = self.create_test_image_bytes(color=(100, 100, 100))
        response = self.client.post(
            "/predict",
            data={"file": (img_bytes, "test.jpg")},
            content_type="multipart/form-data"
        )
        self.assertEqual(response.status_code, 200)
        res = response.get_json()
        self.assertTrue(res["success"])
        self.assertIn("predictedClass", res)
        self.assertIn("confidence", res)
        self.assertTrue(0.0 <= res["confidence"] <= 1.0)
        self.assertEqual(len(res["probabilities"]), 8)
        self.assertEqual(len(res["classProbabilities"]), 8)
        self.assertEqual(res["modelVersion"], "urbanpulse_8class_pothole_stage1")
        print(f"[TEST 05 PASSED] Predict returned: {res['predictedClass']} ({res['confidence']:.4f})")

    def test_06_real_civic_image_if_available(self):
        uploads_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "Urban-Pulse", "uploads"))
        if os.path.exists(uploads_dir):
            jpgs = [os.path.join(uploads_dir, f) for f in os.listdir(uploads_dir) if f.lower().endswith((".jpg", ".png"))]
            if jpgs:
                sample_path = jpgs[0]
                with open(sample_path, "rb") as f:
                    file_bytes = f.read()

                response = self.client.post(
                    "/predict",
                    data={"file": (io.BytesIO(file_bytes), os.path.basename(sample_path))},
                    content_type="multipart/form-data"
                )
                self.assertEqual(response.status_code, 200)
                res = response.get_json()
                self.assertTrue(res["success"])
                self.assertIsNotNone(res["predictedClass"])
                print(f"[TEST 06 PASSED] Real image inference: class={res['predictedClass']}, confidence={res['confidence']:.4f}")

if __name__ == "__main__":
    unittest.main()
