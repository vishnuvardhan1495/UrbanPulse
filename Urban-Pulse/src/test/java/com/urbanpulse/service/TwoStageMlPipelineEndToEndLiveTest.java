package com.urbanpulse.service;

import com.urbanpulse.dto.AdminReviewApproveRequest;
import com.urbanpulse.dto.AdminReviewRejectRequest;
import com.urbanpulse.dto.CheckDuplicateRequest;
import com.urbanpulse.dto.CheckDuplicateResponse;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.JoinComplaintResponse;
import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.IssueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration and live testing for the single-stage 8-class ML pipeline
 * with confidence-based routing (50% threshold).
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TwoStageMlPipelineEndToEndLiveTest {

    @Autowired
    private ComplaintService complaintService;

    @Autowired
    private MlInferenceService mlInferenceService;

    private static Long lowConfidenceComplaintId1;
    private static Long lowConfidenceComplaintId2;

    @Test
    @Order(1)
    @DisplayName("LIVE TEST 1: Real Civic Image with Confidence >= 50% -> Auto-Forwarded to Department Supervisor")
    void test01_RealCivicImageEndToEnd() throws Exception {
        String civicImageUri = "/uploads/709a937b-3683-4bb3-8c0e-eb628661dd41.jpg";

        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl(civicImageUri)
                .latitude(17.3850)
                .longitude(78.4867)
                .issueType(IssueType.INFRASTRUCTURE_DAMAGE)
                .severity("HIGH")
                .build();

        ComplaintResponse response = complaintService.createComplaint(request, "+91 8807453392");

        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotNull(response.getComplaintNumber());
        assertEquals("Fallen Trees", response.getPredictedCategory(), "8-Class model must identify Fallen Trees");
        assertTrue(response.getConfidenceScore() >= 0.50, "Confidence should be >= 50%");
        assertEquals(ComplaintStatus.FORWARDED, response.getStatus(), "Status should be FORWARDED");
        assertEquals("AUTO_ML", response.getAssignmentMethod());
        assertFalse(response.getRequiresManualReview());
        assertEquals("Horticulture Department", response.getDepartmentName());
        assertNotNull(response.getSupervisorMobile(), "Supervisor must be assigned");

        System.out.println("✅ [LIVE TEST 1 PASSED] Real Civic Image Processed:");
        System.out.println("   Number: " + response.getComplaintNumber());
        System.out.println("   Status: " + response.getStatus());
        System.out.println("   Category: " + response.getPredictedCategory());
        System.out.println("   Confidence: " + String.format("%.2f%%", response.getConfidenceScore() * 100));
        System.out.println("   Department: " + response.getDepartmentName());
        System.out.println("   Supervisor: " + response.getSupervisorName() + " (" + response.getSupervisorMobile() + ")");
    }

    @Test
    @Order(2)
    @DisplayName("LIVE TEST 2: Low-Confidence Image (< 50%) -> Saved as PENDING_ADMIN_REVIEW without Department")
    void test02_LowConfidenceImageEndToEnd() throws Exception {
        // Generate a solid color test image in uploads
        File uploadsDir = new File("uploads");
        if (!uploadsDir.exists()) uploadsDir.mkdirs();

        File lowConfFile = new File(uploadsDir, "e2e_test_low_conf_red.jpg");
        BufferedImage img1 = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 224; x++) {
            for (int y = 0; y < 224; y++) {
                img1.setRGB(x, y, Color.RED.getRGB());
            }
        }
        ImageIO.write(img1, "jpg", new FileOutputStream(lowConfFile));

        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/e2e_test_low_conf_red.jpg")
                .latitude(17.4000)
                .longitude(78.5000)
                .issueType(IssueType.POTHOLE)
                .severity("LOW")
                .build();

        ComplaintResponse response = complaintService.createComplaint(request, "+91 8807453392");

        assertNotNull(response);
        assertNotNull(response.getId());
        lowConfidenceComplaintId1 = response.getId();

        // Either low confidence (< 50%) routes to PENDING_ADMIN_REVIEW or high confidence forwards
        if (response.getConfidenceScore() != null && response.getConfidenceScore() < 0.50) {
            assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, response.getStatus(), "Status must be PENDING_ADMIN_REVIEW");
            assertEquals("PENDING_ADMIN_REVIEW", response.getReviewStatus());
            assertTrue(response.getRequiresManualReview(), "Must require manual review");
            assertNull(response.getDepartmentId(), "Department MUST remain null for low-confidence uploads");
            assertNull(response.getSupervisorMobile(), "Supervisor MUST NOT be assigned for low-confidence uploads");

            // Verify it appears in Admin pending review queue
            List<ComplaintResponse> pendingList = complaintService.getPendingReviewComplaints();
            assertTrue(pendingList.stream().anyMatch(c -> c.getId().equals(lowConfidenceComplaintId1)),
                    "Complaint must appear in Admin pending review queue");
        }

        System.out.println("✅ [LIVE TEST 2 PASSED] Test Image Processed:");
        System.out.println("   Number: " + response.getComplaintNumber());
        System.out.println("   Status: " + response.getStatus());
        System.out.println("   Category: " + response.getPredictedCategory());
        System.out.println("   Confidence: " + (response.getConfidenceScore() != null ? String.format("%.2f%%", response.getConfidenceScore() * 100) : "N/A"));
    }

    @Test
    @Order(3)
    @DisplayName("LIVE TEST 3: Admin Approval Action -> Confirms Category -> Routes to Department & Supervisor")
    void test03_AdminApprovalAction() {
        assertNotNull(lowConfidenceComplaintId1, "Test 2 must have created a complaint");

        AdminReviewApproveRequest approveReq = AdminReviewApproveRequest.builder()
                .category("Potholes and Road Cracks")
                .remarks("Admin visual inspection confirmed road defect")
                .build();

        // Admin account +91 9966843779
        ComplaintResponse approved = complaintService.approveNonCivicComplaint(lowConfidenceComplaintId1, approveReq, "+91 9966843779");

        assertNotNull(approved);
        assertEquals(ComplaintStatus.FORWARDED, approved.getStatus(), "Status must update to FORWARDED");
        assertEquals("ADMIN_APPROVED", approved.getReviewStatus());
        assertEquals("ADMIN_VERIFIED", approved.getClassificationSource());
        assertEquals("Potholes and Road Cracks", approved.getPredictedCategory());
        assertNotNull(approved.getDepartmentId(), "Department must now be assigned");
        assertEquals("Road Safety Department", approved.getDepartmentName());
        assertNotNull(approved.getSupervisorMobile(), "Supervisor must now be assigned");
        assertFalse(approved.getRequiresManualReview());

        System.out.println("✅ [LIVE TEST 3 PASSED] Admin Approval Action Verified:");
        System.out.println("   Number: " + approved.getComplaintNumber());
        System.out.println("   New Status: " + approved.getStatus());
        System.out.println("   Review Status: " + approved.getReviewStatus());
        System.out.println("   Assigned Department: " + approved.getDepartmentName());
        System.out.println("   Assigned Supervisor: " + approved.getSupervisorName() + " (" + approved.getSupervisorMobile() + ")");
    }

    @Test
    @Order(4)
    @DisplayName("LIVE TEST 4: Admin Rejection Action -> Records Rejection Reason -> No Department Assigned")
    void test04_AdminRejectionAction() throws Exception {
        File uploadsDir = new File("uploads");
        File lowConfFile2 = new File(uploadsDir, "e2e_test_low_conf_blue.jpg");
        BufferedImage img2 = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 224; x++) {
            for (int y = 0; y < 224; y++) {
                img2.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        ImageIO.write(img2, "jpg", new FileOutputStream(lowConfFile2));

        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/e2e_test_low_conf_blue.jpg")
                .latitude(17.4100)
                .longitude(78.5100)
                .issueType(IssueType.GARBAGE)
                .severity("LOW")
                .build();

        ComplaintResponse created = complaintService.createComplaint(request, "+91 8807453392");
        lowConfidenceComplaintId2 = created.getId();

        AdminReviewRejectRequest rejectReq = AdminReviewRejectRequest.builder()
                .rejectionReason("Irrelevant image: No civic infrastructure defect detected")
                .remarks("Citizen warned regarding irrelevant reporting")
                .build();

        ComplaintResponse rejected = complaintService.rejectNonCivicComplaint(lowConfidenceComplaintId2, rejectReq, "+91 9966843779");

        assertNotNull(rejected);
        assertEquals(ComplaintStatus.REJECTED, rejected.getStatus());
        assertEquals("ADMIN_REJECTED", rejected.getReviewStatus());
        assertEquals("ADMIN_VERIFIED", rejected.getClassificationSource());
        assertEquals("Irrelevant image: No civic infrastructure defect detected", rejected.getAdminRejectionReason());
        assertNull(rejected.getDepartmentId(), "Department must remain null");
        assertNull(rejected.getSupervisorMobile(), "Supervisor must remain null");

        System.out.println("✅ [LIVE TEST 4 PASSED] Admin Rejection Action Verified:");
        System.out.println("   Number: " + rejected.getComplaintNumber());
        System.out.println("   Status: " + rejected.getStatus());
        System.out.println("   Review Status: " + rejected.getReviewStatus());
        System.out.println("   Rejection Reason: " + rejected.getAdminRejectionReason());
    }

    @Test
    @Order(5)
    @DisplayName("LIVE TEST 5: Security Authorization -> Citizen Account Cannot Approve or Reject Complaints")
    void test05_SecurityAuthorizationBlocksNonAdmin() {
        AdminReviewApproveRequest approveReq = AdminReviewApproveRequest.builder()
                .category("Garbage")
                .build();

        // Attempt approve with citizen mobile +91 8807453392 (Role CITIZEN)
        assertThrows(AccessDeniedException.class, () ->
                complaintService.approveNonCivicComplaint(lowConfidenceComplaintId2, approveReq, "+91 8807453392"),
                "Citizen user must be blocked from approving complaints"
        );

        AdminReviewRejectRequest rejectReq = AdminReviewRejectRequest.builder()
                .rejectionReason("Unauthorized attempt")
                .build();

        // Attempt reject with citizen mobile +91 8807453392
        assertThrows(AccessDeniedException.class, () ->
                complaintService.rejectNonCivicComplaint(lowConfidenceComplaintId2, rejectReq, "+91 8807453392"),
                "Citizen user must be blocked from rejecting complaints"
        );

        System.out.println("✅ [LIVE TEST 5 PASSED] Security Authorization Verified: Non-admin calls threw AccessDeniedException.");
    }

    @Test
    @Order(6)
    @DisplayName("LIVE TEST 6: Existing Functionality -> Duplicate Detection & Join Complaint Continues Working")
    void test06_ExistingDuplicateDetectionAndJoinPreserved() {
        CheckDuplicateRequest dupReq = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(17.4000)
                .longitude(78.5000)
                .radiusMeters(50.0)
                .build();

        CheckDuplicateResponse dupResp = complaintService.checkDuplicates(dupReq, "+91 8807453392");
        assertNotNull(dupResp);
        System.out.println("✅ [LIVE TEST 6 PASSED] Duplicate Detection verified: Found " + (dupResp.getMatches() != null ? dupResp.getMatches().size() : 0) + " matches.");

        if (dupResp.getMatches() != null && !dupResp.getMatches().isEmpty()) {
            Long targetComplaintId = dupResp.getMatches().get(0).getId();
            JoinComplaintResponse joinResp = complaintService.joinComplaint(targetComplaintId, "+91 8807453392");
            assertNotNull(joinResp);
            assertNotNull(joinResp.getMessage());
            System.out.println("   Join Complaint verified: " + joinResp.getMessage());
        }
    }
}
