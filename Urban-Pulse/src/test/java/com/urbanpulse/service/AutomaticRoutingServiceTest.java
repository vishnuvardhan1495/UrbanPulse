package com.urbanpulse.service;

import com.urbanpulse.dto.AdminManualAssignRequest;
import com.urbanpulse.dto.AdminReviewApproveRequest;
import com.urbanpulse.dto.AdminReviewRejectRequest;
import com.urbanpulse.dto.MlPredictionResponse;
import com.urbanpulse.entity.Complaint;
import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.ComplaintStatusHistory;
import com.urbanpulse.entity.Department;
import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.ComplaintRepository;
import com.urbanpulse.repository.ComplaintStatusHistoryRepository;
import com.urbanpulse.repository.DepartmentRepository;
import com.urbanpulse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomaticRoutingServiceTest {

    @Mock
    private MlInferenceService mlInferenceService;

    @Spy
    private CategoryDepartmentMappingService categoryDepartmentMappingService = new CategoryDepartmentMappingService();

    @Mock
    private ComplaintRepository complaintRepository;

    @Mock
    private ComplaintStatusHistoryRepository historyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private NotificationService notificationService;

    @Spy
    private SlaService slaService = new SlaService();

    @InjectMocks
    private AutomaticRoutingService routingService;

    private User citizen;
    private User admin;
    private User sanitationSupervisor;
    private User roadsSupervisor;
    private Department sanitationDept;
    private Department roadsDept;
    private Complaint complaint;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(routingService, "confidenceThreshold", 0.50);

        citizen = User.builder().mobileNumber("+91 9000000001").name("Test Citizen").role(Role.CITIZEN).fcmToken("token1").build();
        admin = User.builder().mobileNumber("+91 9000000002").name("Test Admin").role(Role.ADMIN).build();

        sanitationDept = Department.builder().id(1L).name("Sanitation Department").description("Garbage").build();
        roadsDept = Department.builder().id(2L).name("Road Safety Department").description("Damaged Road Signs, Potholes and Road Cracks").build();

        sanitationSupervisor = User.builder().mobileNumber("+91 9000000003").name("Sanitation Sup").role(Role.SUPERVISOR).department(sanitationDept).fcmToken("token-sup1").build();
        roadsSupervisor = User.builder().mobileNumber("+91 9000000004").name("Road Safety Sup").role(Role.SUPERVISOR).department(roadsDept).fcmToken("token-sup2").build();

        complaint = Complaint.builder()
                .id(100L)
                .complaintNumber("CMP-100")
                .status(ComplaintStatus.SUBMITTED)
                .citizen(citizen)
                .imageUrl("/uploads/test.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        lenient().when(complaintRepository.save(any(Complaint.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // =========================================================================
    // REQUIRED TEST SCENARIOS: Low Confidence (< 50%) -> Admin Review
    // =========================================================================

    @ParameterizedTest(name = "Confidence {0} (< 50%) -> Admin Review (PENDING_ADMIN_REVIEW)")
    @ValueSource(doubles = {0.20, 0.35, 0.49, 0.4999})
    @DisplayName("Confidence < 50% (20%, 35%, 49%, 49.99%): Routes to Admin Review without department assignment")
    void testLowConfidenceRoutesToAdminReview(double lowConfidence) {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(lowConfidence)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, result.getStatus(), "Low confidence complaints must be PENDING_ADMIN_REVIEW");
        assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", result.getAssignmentMethod());
        assertTrue(result.getRequiresManualReview());
        assertEquals("PENDING_ADMIN_REVIEW", result.getReviewStatus());
        assertEquals("PENDING_REVIEW", result.getClassificationStatus());
        assertNull(result.getDepartment(), "Must not assign department for low-confidence complaint");
        assertNull(result.getAssignedSupervisor(), "Must not assign supervisor for low-confidence complaint");
        assertEquals("Garbage", result.getPredictedCategory(), "Should store predicted category for admin reference");
        assertEquals(lowConfidence, result.getConfidenceScore(), "Should store confidence score for admin reference");
        assertNotNull(result.getAdminReviewReason());
        assertTrue(result.getAdminReviewReason().contains("< 50%"));

        verifyNoInteractions(notificationService);
    }

    // =========================================================================
    // REQUIRED TEST SCENARIOS: High Confidence (>= 50%) -> Department Supervisor
    // =========================================================================

    @ParameterizedTest(name = "Confidence {0} (>= 50%) -> Department Supervisor (FORWARDED)")
    @ValueSource(doubles = {0.50, 0.65, 0.85, 0.99})
    @DisplayName("Confidence >= 50% (50.00%, 65%, 85%, 99%): Auto-forwards to respective Department Supervisor")
    void testHighConfidenceRoutesToSupervisor(double highConfidence) {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(highConfidence)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(List.of(sanitationSupervisor));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.FORWARDED, result.getStatus(), "Confidence >= 50% must be FORWARDED");
        assertEquals("AUTO_ML", result.getAssignmentMethod());
        assertFalse(result.getRequiresManualReview());
        assertEquals("AUTO_ROUTED", result.getReviewStatus());
        assertEquals("CLASSIFIED", result.getClassificationStatus());
        assertEquals(sanitationDept, result.getDepartment(), "Must assign to Sanitation Department");
        assertEquals(sanitationSupervisor, result.getAssignedSupervisor(), "Must assign to Sanitation Supervisor");
        assertEquals("Garbage", result.getPredictedCategory());
        assertEquals(highConfidence, result.getConfidenceScore());
        assertNotNull(result.getAssignedAt());

        verify(notificationService).sendComplaintStatusNotification(eq("token1"), eq("CMP-100"), eq(ComplaintStatus.FORWARDED), eq("Sanitation Department"));
        verify(notificationService).sendComplaintStatusNotification(eq("token-sup1"), eq("CMP-100"), eq(ComplaintStatus.FORWARDED), eq("Sanitation Department"));
    }

    // =========================================================================
    // ALL 8 CLASSIFICATION CATEGORIES ROUTE TO CORRECT DEPARTMENTS
    // =========================================================================

    @ParameterizedTest(name = "Category ''{0}'' maps to ''{1}''")
    @CsvSource({
            "Damaged concrete structures, Engineering Department",
            "DamagedElectricalPoles, Electrical Department",
            "DamagedRoadSigns, Road Safety Department",
            "DeadAnimalsPollution, Public Health Department",
            "FallenTrees, Horticulture Department",
            "Garbage, Sanitation Department",
            "IllegalParking, Traffic Department",
            "Potholes and RoadCracks, Road Safety Department"
    })
    @DisplayName("Verify all 8 classification categories map to correct departments")
    void testAll8CategoriesMapping(String rawCategory, String expectedDepartmentName) {
        Department targetDept = Department.builder().id(99L).name(expectedDepartmentName).build();
        User targetSup = User.builder().mobileNumber("+91 9999999999").role(Role.SUPERVISOR).department(targetDept).build();

        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass(rawCategory)
                .confidence(0.90)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName(expectedDepartmentName)).thenReturn(Optional.of(targetDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, targetDept.getId())).thenReturn(List.of(targetSup));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.FORWARDED, result.getStatus());
        assertEquals(expectedDepartmentName, result.getDepartment().getName());
        assertEquals(targetSup, result.getAssignedSupervisor());
    }

    // =========================================================================
    // FALLBACK AND ADMIN REVIEW TESTS
    // =========================================================================

    @Test
    @DisplayName("ML Service Failure: Fallback routes to Admin for manual review (PENDING_ADMIN_REVIEW)")
    void testMlServiceFailureFallback() {
        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.empty());

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, result.getStatus());
        assertEquals("MANUAL_REVIEW_ML_FAILED", result.getAssignmentMethod());
        assertTrue(result.getRequiresManualReview());
        assertNotNull(result.getAdminReviewReason());
        assertNull(result.getDepartment());
        assertNull(result.getAssignedSupervisor());

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("Mapped department has no active supervisor: Retains department and routes to Admin for review")
    void testNoSupervisorInDepartmentFallback() {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.85)
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(Collections.emptyList());

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, result.getStatus());
        assertEquals("MANUAL_REVIEW_NO_SUPERVISOR", result.getAssignmentMethod());
        assertTrue(result.getRequiresManualReview());
        assertEquals(sanitationDept, result.getDepartment());
        assertNull(result.getAssignedSupervisor());
    }

    @Test
    @DisplayName("Admin manual approval: Approves low-confidence complaint and forwards to selected department supervisor")
    void testAdminManualApproval() {
        complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);
        complaint.setRequiresManualReview(true);
        complaint.setPredictedCategory("Garbage");
        complaint.setConfidenceScore(0.35);

        when(userRepository.findByMobileNumber("+91 9000000002")).thenReturn(Optional.of(admin));
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(List.of(sanitationSupervisor));

        AdminReviewApproveRequest request = new AdminReviewApproveRequest();
        request.setCategory("Garbage");
        request.setRemarks("Admin confirmed garbage in image");

        Complaint approved = routingService.approveLowConfidenceComplaintByAdmin(100L, request, "+91 9000000002");

        assertNotNull(approved);
        assertEquals(ComplaintStatus.FORWARDED, approved.getStatus());
        assertEquals("ADMIN_MANUAL_APPROVAL", approved.getAssignmentMethod());
        assertFalse(approved.getRequiresManualReview());
        assertEquals("ADMIN_APPROVED", approved.getReviewStatus());
        assertEquals("+91 9000000002", approved.getAdminReviewedBy());
        assertEquals(sanitationDept, approved.getDepartment());
        assertEquals(sanitationSupervisor, approved.getAssignedSupervisor());
    }

    @Test
    @DisplayName("Admin manual rejection: Rejects low-confidence complaint with mandatory reason")
    void testAdminManualRejection() {
        complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);
        complaint.setRequiresManualReview(true);

        when(userRepository.findByMobileNumber("+91 9000000002")).thenReturn(Optional.of(admin));
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));

        AdminReviewRejectRequest request = new AdminReviewRejectRequest();
        request.setRejectionReason("Not a civic issue, personal photo");
        request.setRemarks("Selfie uploaded");

        Complaint rejected = routingService.rejectLowConfidenceComplaintByAdmin(100L, request, "+91 9000000002");

        assertNotNull(rejected);
        assertEquals(ComplaintStatus.REJECTED, rejected.getStatus());
        assertEquals("ADMIN_REJECTED", rejected.getReviewStatus());
        assertEquals("REJECTED", rejected.getClassificationStatus());
        assertEquals("Not a civic issue, personal photo", rejected.getAdminRejectionReason());
        assertFalse(rejected.getRequiresManualReview());
        assertNull(rejected.getDepartment());
    }
}
