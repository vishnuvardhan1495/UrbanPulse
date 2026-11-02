package com.urbanpulse.service;

import com.urbanpulse.dto.AdminManualAssignRequest;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
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
        roadsDept = Department.builder().id(2L).name("Roads and Infrastructure Department").description("Potholes and Road Cracks").build();

        sanitationSupervisor = User.builder().mobileNumber("+91 9000000003").name("Sanitation Sup").role(Role.SUPERVISOR).department(sanitationDept).fcmToken("token-sup1").build();
        roadsSupervisor = User.builder().mobileNumber("+91 9000000004").name("Roads Sup").role(Role.SUPERVISOR).department(roadsDept).fcmToken("token-sup2").build();

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

    @Test
    @DisplayName("Confidence > 50% (Garbage, 87%): Automatically forwards to Sanitation Department supervisor")
    void testAutoForwardHighConfidenceGarbage() {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.87)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(List.of(sanitationSupervisor));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.FORWARDED, result.getStatus());
        assertEquals("Garbage", result.getPredictedCategory());
        assertEquals(0.87, result.getConfidenceScore());
        assertEquals("AUTOMATIC_ML", result.getAssignmentMethod());
        assertFalse(result.getRequiresManualReview());
        assertNull(result.getAdminReviewReason());
        assertEquals(sanitationDept, result.getDepartment());
        assertEquals(sanitationSupervisor, result.getAssignedSupervisor());
        assertNotNull(result.getAssignedAt());

        verify(historyRepository).save(any(ComplaintStatusHistory.class));
        verify(notificationService).sendComplaintStatusNotification(eq("token1"), eq("CMP-100"), eq(ComplaintStatus.FORWARDED), eq("Sanitation Department"));
        verify(notificationService).sendComplaintStatusNotification(eq("token-sup1"), eq("CMP-100"), eq(ComplaintStatus.FORWARDED), eq("Sanitation Department"));
    }

    @Test
    @DisplayName("Confidence > 50% (Potholes and RoadCracks, 75%): Automatically forwards to Roads and Infrastructure Department supervisor")
    void testAutoForwardHighConfidencePotholes() {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Potholes and RoadCracks")
                .confidence(0.75)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Roads and Infrastructure Department")).thenReturn(Optional.of(roadsDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, roadsDept.getId())).thenReturn(List.of(roadsSupervisor));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.FORWARDED, result.getStatus());
        assertEquals("Potholes and Road Cracks", result.getPredictedCategory());
        assertEquals(0.75, result.getConfidenceScore());
        assertEquals("AUTOMATIC_ML", result.getAssignmentMethod());
        assertFalse(result.getRequiresManualReview());
        assertEquals(roadsDept, result.getDepartment());
        assertEquals(roadsSupervisor, result.getAssignedSupervisor());
    }

    @Test
    @DisplayName("Exact 50% Confidence (Garbage, 50%): Routes to Admin for manual review")
    void testExactFiftyPercentBoundaryRoutesToAdmin() {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.50)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.SUBMITTED, result.getStatus());
        assertTrue(result.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", result.getAssignmentMethod());
        assertNotNull(result.getAdminReviewReason());
        assertTrue(result.getAdminReviewReason().contains("<= 50%"));
        assertNull(result.getAssignedSupervisor());
    }

    @Test
    @DisplayName("Confidence <= 50% (Garbage, 49%): Routes to Admin for manual review")
    void testFortyNinePercentRoutesToAdmin() {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.49)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.SUBMITTED, result.getStatus());
        assertTrue(result.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", result.getAssignmentMethod());
    }

    @Test
    @DisplayName("ML service failure (empty response): Safely routes to Admin without losing complaint")
    void testMlServiceFailureRoutesToAdmin() {
        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.empty());

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.SUBMITTED, result.getStatus());
        assertTrue(result.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_ML_FAILED", result.getAssignmentMethod());
        assertNotNull(result.getAdminReviewReason());
    }

    @Test
    @DisplayName("No active supervisor found in mapped department: Retains department and routes to Admin")
    void testNoSupervisorInDepartmentRoutesToAdmin() {
        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.92)
                .build();

        when(mlInferenceService.predictImage(any(), any())).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(Collections.emptyList());

        Complaint result = routingService.routeComplaint(complaint, new byte[]{1, 2, 3}, "test.jpg");

        assertNotNull(result);
        assertEquals(ComplaintStatus.SUBMITTED, result.getStatus());
        assertTrue(result.getRequiresManualReview());
        assertEquals("ML_UNASSIGNED_NO_SUPERVISOR", result.getAssignmentMethod());
        assertEquals(sanitationDept, result.getDepartment());
        assertNull(result.getAssignedSupervisor());
    }

    @Test
    @DisplayName("Admin manual assignment: Reassigns department/supervisor, clears manual review flag and sets FORWARDED")
    void testAdminManualAssign() {
        complaint.setRequiresManualReview(true);
        complaint.setAdminReviewReason("Low confidence");

        when(userRepository.findByMobileNumber("+91 9000000002")).thenReturn(Optional.of(admin));
        when(complaintRepository.findById(100L)).thenReturn(Optional.of(complaint));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, 1L)).thenReturn(List.of(sanitationSupervisor));

        AdminManualAssignRequest request = AdminManualAssignRequest.builder()
                .departmentId(1L)
                .category("Garbage")
                .remarks("Reviewed by admin")
                .build();

        Complaint updated = routingService.manualAssignByAdmin(100L, request, "+91 9000000002");

        assertNotNull(updated);
        assertEquals(ComplaintStatus.FORWARDED, updated.getStatus());
        assertEquals("MANUAL_ADMIN", updated.getAssignmentMethod());
        assertFalse(updated.getRequiresManualReview());
        assertNull(updated.getAdminReviewReason());
        assertEquals(sanitationDept, updated.getDepartment());
        assertEquals(sanitationSupervisor, updated.getAssignedSupervisor());
        assertNotNull(updated.getAssignedAt());
    }
}
