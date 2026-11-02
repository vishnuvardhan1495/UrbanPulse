package com.urbanpulse.service;

import com.urbanpulse.dto.MlPredictionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LiveMlInferenceIntegrationTest {

    @Autowired
    private MlInferenceService mlInferenceService;

    @Autowired
    private FileStorageService fileStorageService;

    @Test
    @DisplayName("Should successfully connect to live Flask service, verify health and perform real prediction")
    void testLiveFlaskInference() throws Exception {
        // 1. Health check
        boolean healthy = mlInferenceService.isHealthy();
        assertTrue(healthy, "Flask ML service must be reachable and healthy at http://localhost:5001/health");

        // 2. Real image prediction
        File uploadsDir = new File("uploads");
        File[] images = uploadsDir.listFiles((dir, name) -> name.endsWith(".jpg"));
        assertNotNull(images);
        assertTrue(images.length > 0, "Uploads directory must have test images");

        File testImageFile = images[0];
        byte[] imageBytes = Files.readAllBytes(testImageFile.toPath());

        Optional<MlPredictionResponse> predictionOpt = mlInferenceService.predictImage(imageBytes, testImageFile.getName());
        assertTrue(predictionOpt.isPresent(), "Prediction response should be present from live Flask service");

        MlPredictionResponse prediction = predictionOpt.get();
        assertNotNull(prediction.getPredictedClass(), "Predicted class should not be null");
        assertNotNull(prediction.getConfidence(), "Confidence should not be null");
        assertTrue(prediction.getConfidence() >= 0.0 && prediction.getConfidence() <= 1.0, "Confidence must be between 0 and 1");
        assertEquals("urbanpulse_8class_pothole_stage1", prediction.getModelVersion());

        System.out.println("LIVE INFERENCE SUCCESS:");
        System.out.println("  Image: " + testImageFile.getName());
        System.out.println("  Class: " + prediction.getPredictedClass());
        System.out.println("  Confidence: " + prediction.getConfidence());
        System.out.println("  Probabilities list size: " + prediction.getProbabilitiesAsList().size());
        assertEquals(8, prediction.getProbabilitiesAsList().size(), "Probabilities should contain 8 values");
    }

    @Autowired
    private ComplaintService complaintService;

    @Test
    @DisplayName("End-to-End: Citizen creates complaint with real garbage image -> Flask classifies -> Auto forwards to Sanitation supervisor")
    void testLiveEndToEndGarbageComplaintForwarding() throws Exception {
        // Upload image 0737b13c-d680-448c-a1af-acb149bcdd23.jpg (which model classifies as Garbage ~91%)
        String imageUri = "/uploads/0737b13c-d680-448c-a1af-acb149bcdd23.jpg";

        com.urbanpulse.dto.CreateComplaintRequest request = com.urbanpulse.dto.CreateComplaintRequest.builder()
                .imageUrl(imageUri)
                .latitude(17.3850)
                .longitude(78.4867)
                .issueType(com.urbanpulse.entity.IssueType.GARBAGE)
                .severity("HIGH")
                .build();

        // Citizen mobile from database
        com.urbanpulse.dto.ComplaintResponse response = complaintService.createComplaint(request, "+91 9966843779");

        assertNotNull(response);
        assertNotNull(response.getComplaintNumber());
        System.out.println("CREATED COMPLAINT:");
        System.out.println("  Number: " + response.getComplaintNumber());
        System.out.println("  Status: " + response.getStatus());
        System.out.println("  Category: " + response.getPredictedCategory());
        System.out.println("  Confidence: " + response.getConfidenceScore());
        System.out.println("  Method: " + response.getAssignmentMethod());
        System.out.println("  Department: " + response.getDepartmentName());
        System.out.println("  Supervisor: " + response.getSupervisorName() + " (" + response.getSupervisorMobile() + ")");

        assertEquals(com.urbanpulse.entity.ComplaintStatus.FORWARDED, response.getStatus());
        assertEquals("Garbage", response.getPredictedCategory());
        assertTrue(response.getConfidenceScore() > 0.50);
        assertEquals("AUTO_ML", response.getAssignmentMethod());
        assertFalse(response.getRequiresManualReview());
        assertEquals("Sanitation Department", response.getDepartmentName());
        assertNotNull(response.getSupervisorMobile());
    }
}
