package com.urbanpulse.service;

import com.urbanpulse.dto.CheckDuplicateRequest;
import com.urbanpulse.dto.CheckDuplicateResponse;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.MlPredictionResponse;
import com.urbanpulse.entity.Complaint;
import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.ComplaintStatusHistory;
import com.urbanpulse.entity.Department;
import com.urbanpulse.entity.IssueType;
import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.ComplaintParticipantRepository;
import com.urbanpulse.repository.ComplaintRepository;
import com.urbanpulse.repository.ComplaintStatusHistoryRepository;
import com.urbanpulse.repository.DepartmentRepository;
import com.urbanpulse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive integration tests for citizen complaint submission flow.
 * Validates the single-stage 8-class ML pipeline and confidence routing:
 *  1. Valid image and confidence > 50% -> Department Supervisor (FORWARDED)
 *  2. Valid image and confidence exactly 50% (0.50) -> Department Supervisor (FORWARDED)
 *  3. Valid image and confidence < 50% -> Admin Review (PENDING_ADMIN_REVIEW)
 *  4. ML service unavailable -> Admin Review (PENDING_ADMIN_REVIEW)
 *  5. No active department supervisor -> Admin Review (PENDING_ADMIN_REVIEW)
 *  6. Invalid or corrupted image
 *  7. Existing duplicate complaint
 */
@ExtendWith(MockitoExtension.class)
class ComplaintSubmissionFlowTest {

    @Mock
    private ComplaintRepository complaintRepository;

    @Mock
    private ComplaintParticipantRepository complaintParticipantRepository;

    @Mock
    private ComplaintStatusHistoryRepository historyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Spy
    private SlaService slaService = new SlaService();

    @Mock
    private NotificationService notificationService;

    @Mock
    private MlInferenceService mlInferenceService;

    @Spy
    private CategoryDepartmentMappingService categoryDepartmentMappingService = new CategoryDepartmentMappingService();

    private AutomaticRoutingService automaticRoutingService;
    private ComplaintService complaintService;

    private User citizen;
    private User sanitationSupervisor;
    private Department sanitationDept;

    @BeforeEach
    void setUp() {
        automaticRoutingService = new AutomaticRoutingService(
                mlInferenceService,
                categoryDepartmentMappingService,
                complaintRepository,
                historyRepository,
                userRepository,
                departmentRepository,
                notificationService,
                slaService
        );
        ReflectionTestUtils.setField(automaticRoutingService, "confidenceThreshold", 0.50);

        complaintService = new ComplaintService(
                complaintRepository,
                complaintParticipantRepository,
                historyRepository,
                userRepository,
                departmentRepository,
                fileStorageService,
                slaService,
                notificationService,
                automaticRoutingService
        );

        citizen = User.builder()
                .mobileNumber("+91 9000000001")
                .name("Citizen John")
                .role(Role.CITIZEN)
                .fcmToken("fcm-citizen-token")
                .build();

        sanitationDept = Department.builder()
                .id(1L)
                .name("Sanitation Department")
                .description("Garbage")
                .build();

        sanitationSupervisor = User.builder()
                .mobileNumber("+91 9000000002")
                .name("Supervisor Jane")
                .role(Role.SUPERVISOR)
                .department(sanitationDept)
                .fcmToken("fcm-supervisor-token")
                .build();

        lenient().when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));
        lenient().when(complaintRepository.save(any(Complaint.class))).thenAnswer(invocation -> {
            Complaint c = invocation.getArgument(0);
            if (c.getId() == null) {
                c.setId(101L);
                c.setCreatedAt(LocalDateTime.now());
            }
            return c;
        });
        lenient().when(complaintParticipantRepository.countByComplaintId(anyLong())).thenReturn(0L);
    }

    @Test
    @DisplayName("Scenario 1: Valid image with confidence > 50% automatically forwards to supervisor")
    void testScenario1_HighConfidence_AutoForwardToSupervisor() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/garbage.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .issueType(IssueType.GARBAGE)
                .severity("MEDIUM")
                .build();

        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(fileStorageService.loadFileBytes("/uploads/garbage.jpg")).thenReturn(dummyBytes);

        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.88)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();
        when(mlInferenceService.predictImage(dummyBytes, "/uploads/garbage.jpg")).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(List.of(sanitationSupervisor));

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.FORWARDED, response.getStatus());
        assertEquals("Garbage", response.getPredictedCategory());
        assertEquals(0.88, response.getConfidenceScore());
        assertEquals("AUTO_ML", response.getAssignmentMethod());
        assertFalse(response.getRequiresManualReview());
        assertEquals("Sanitation Department", response.getDepartmentName());
        assertEquals("+91 9000000002", response.getSupervisorMobile());
        assertNotNull(response.getAssignedAt());

        verify(historyRepository, atLeast(1)).save(any(ComplaintStatusHistory.class));
    }

    @Test
    @DisplayName("Scenario 2: Valid image with confidence exactly 50% (0.50) forwards to department supervisor")
    void testScenario2_ExactFiftyPercent_ForwardsToSupervisor() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/garbage_border.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .issueType(IssueType.GARBAGE)
                .build();

        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(fileStorageService.loadFileBytes("/uploads/garbage_border.jpg")).thenReturn(dummyBytes);

        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.50)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();
        when(mlInferenceService.predictImage(dummyBytes, "/uploads/garbage_border.jpg")).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(List.of(sanitationSupervisor));

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.FORWARDED, response.getStatus());
        assertFalse(response.getRequiresManualReview());
        assertEquals("AUTO_ML", response.getAssignmentMethod());
        assertEquals("Sanitation Department", response.getDepartmentName());
        assertEquals("+91 9000000002", response.getSupervisorMobile());
    }

    @Test
    @DisplayName("Scenario 3: Valid image with confidence below 50% (e.g. 49.99% or 35%) routes to Admin Review")
    void testScenario3_LowConfidence_RoutesToAdmin() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/unclear.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(fileStorageService.loadFileBytes("/uploads/unclear.jpg")).thenReturn(dummyBytes);

        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.35)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();
        when(mlInferenceService.predictImage(dummyBytes, "/uploads/unclear.jpg")).thenReturn(Optional.of(prediction));

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", response.getAssignmentMethod());
        assertEquals("Garbage", response.getPredictedCategory());
        assertEquals(0.35, response.getConfidenceScore());
        assertNull(response.getDepartmentName());
        assertNull(response.getSupervisorMobile());
    }

    @Test
    @DisplayName("Scenario 4: ML service unavailable safely routes to Admin without HTTP 500 error")
    void testScenario4_MlServiceUnavailable_RoutesToAdminWithoutError() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/pothole.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(fileStorageService.loadFileBytes("/uploads/pothole.jpg")).thenReturn(dummyBytes);
        when(mlInferenceService.predictImage(dummyBytes, "/uploads/pothole.jpg")).thenReturn(Optional.empty());

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_ML_FAILED", response.getAssignmentMethod());
        assertNull(response.getDepartmentName());
        assertNull(response.getSupervisorMobile());
    }

    @Test
    @DisplayName("Scenario 5: No active supervisor in department routes to Admin for review")
    void testScenario5_NoActiveSupervisor_RoutesToAdmin() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/garbage.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(fileStorageService.loadFileBytes("/uploads/garbage.jpg")).thenReturn(dummyBytes);

        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.85)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();
        when(mlInferenceService.predictImage(dummyBytes, "/uploads/garbage.jpg")).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(Collections.emptyList());

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.PENDING_ADMIN_REVIEW, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_NO_SUPERVISOR", response.getAssignmentMethod());
        assertEquals("Sanitation Department", response.getDepartmentName());
        assertNull(response.getSupervisorMobile());
    }

    @Test
    @DisplayName("Scenario 6: Missing image or invalid coordinates throws 400 validation error")
    void testScenario6_InvalidInput_ThrowsBadRequest() {
        CreateComplaintRequest invalidCoords = CreateComplaintRequest.builder()
                .imageUrl("/uploads/valid.jpg")
                .latitude(95.0)
                .longitude(78.4867)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                complaintService.createComplaint(invalidCoords, "+91 9000000001"));

        CreateComplaintRequest missingImage = CreateComplaintRequest.builder()
                .imageUrl("   ")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                complaintService.createComplaint(missingImage, "+91 9000000001"));
    }

    @Test
    @DisplayName("Scenario 7: Duplicate detection within 30m radius")
    void testScenario7_DuplicateComplaintDetection() {
        Complaint existingComplaint = Complaint.builder()
                .id(50L)
                .complaintNumber("CMP-EXISTING-01")
                .status(ComplaintStatus.FORWARDED)
                .issueType(IssueType.POTHOLE)
                .latitude(17.385000)
                .longitude(78.486700)
                .imageUrl("/uploads/existing.jpg")
                .citizen(citizen)
                .department(sanitationDept)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        when(complaintRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(existingComplaint));

        CheckDuplicateRequest checkRequest = CheckDuplicateRequest.builder()
                .latitude(17.385050)
                .longitude(78.486750)
                .radiusMeters(30.0)
                .build();

        CheckDuplicateResponse checkResponse = complaintService.checkDuplicates(checkRequest, "+91 9000000001");

        assertNotNull(checkResponse);
        assertTrue(checkResponse.isDuplicateFound());
        assertEquals(1, checkResponse.getMatchCount());
        assertEquals(1, checkResponse.getMatches().size());
        assertEquals("CMP-EXISTING-01", checkResponse.getMatches().get(0).getComplaintNumber());
    }
}
