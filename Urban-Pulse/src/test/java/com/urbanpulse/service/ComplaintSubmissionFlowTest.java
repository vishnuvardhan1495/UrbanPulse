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
 * Validates the 7 required scenarios:
 *  1. Valid image and confidence > 50%
 *  2. Valid image and confidence exactly 50%
 *  3. Valid image and confidence < 50%
 *  4. ML service unavailable
 *  5. No active department supervisor
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
        assertEquals("AUTOMATIC_ML", response.getAssignmentMethod());
        assertFalse(response.getRequiresManualReview());
        assertEquals("Sanitation Department", response.getDepartmentName());
        assertEquals("+91 9000000002", response.getSupervisorMobile());
        assertNotNull(response.getAssignedAt());

        verify(historyRepository, atLeast(1)).save(any(ComplaintStatusHistory.class));
    }

    @Test
    @DisplayName("Scenario 2: Valid image with confidence exactly 50% routes to Admin for manual review")
    void testScenario2_ExactFiftyPercent_RoutesToAdmin() {
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

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", response.getAssignmentMethod());
        assertTrue(response.getAdminReviewReason().contains("<= 50%"));
        assertNull(response.getSupervisorMobile());
    }

    @Test
    @DisplayName("Scenario 3: Valid image with confidence below 50% routes to Admin for manual review")
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
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_LOW_CONFIDENCE", response.getAssignmentMethod());
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
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_ML_FAILED", response.getAssignmentMethod());
    }

    @Test
    @DisplayName("Scenario 5: No active department supervisor routes to Admin with department retained")
    void testScenario5_NoSupervisor_RoutesToAdminWithDepartment() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/garbage.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(fileStorageService.loadFileBytes("/uploads/garbage.jpg")).thenReturn(dummyBytes);

        MlPredictionResponse prediction = MlPredictionResponse.builder()
                .predictedClass("Garbage")
                .confidence(0.95)
                .modelVersion("urbanpulse_8class_pothole_stage1")
                .build();
        when(mlInferenceService.predictImage(dummyBytes, "/uploads/garbage.jpg")).thenReturn(Optional.of(prediction));
        when(departmentRepository.findByName("Sanitation Department")).thenReturn(Optional.of(sanitationDept));
        when(userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, sanitationDept.getId())).thenReturn(Collections.emptyList());

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("ML_UNASSIGNED_NO_SUPERVISOR", response.getAssignmentMethod());
        assertEquals("Sanitation Department", response.getDepartmentName());
        assertNull(response.getSupervisorMobile());
    }

    @Test
    @DisplayName("Scenario 6: Invalid or unreadable image safely routes to Admin without failure")
    void testScenario6_UnreadableImage_SafeAdminRoute() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/corrupt.jpg")
                .latitude(17.3850)
                .longitude(78.4867)
                .build();

        when(fileStorageService.loadFileBytes("/uploads/corrupt.jpg")).thenReturn(null);

        ComplaintResponse response = complaintService.createComplaint(request, "+91 9000000001");

        assertNotNull(response);
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertTrue(response.getRequiresManualReview());
        assertEquals("MANUAL_REVIEW_ML_FAILED", response.getAssignmentMethod());
    }

    @Test
    @DisplayName("Scenario 7: Duplicate detection identifies existing complaint within 30m radius")
    void testScenario7_DuplicateDetection_FindsMatchingComplaint() {
        Complaint existingComplaint = Complaint.builder()
                .id(200L)
                .complaintNumber("CMP-200")
                .status(ComplaintStatus.SUBMITTED)
                .issueType(IssueType.POTHOLE)
                .latitude(17.3850)
                .longitude(78.4867)
                .imageUrl("/uploads/existing_pothole.jpg")
                .createdAt(LocalDateTime.now().minusHours(1))
                .citizen(citizen)
                .build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(List.of(existingComplaint));

        CheckDuplicateRequest checkRequest = CheckDuplicateRequest.builder()
                .latitude(17.38505) // ~5.5 meters away
                .longitude(78.4867)
                .issueType(IssueType.POTHOLE)
                .imageUrl("/uploads/new_pothole.jpg")
                .radiusMeters(30.0)
                .build();

        CheckDuplicateResponse duplicateResponse = complaintService.checkDuplicates(checkRequest, "+91 9000000001");

        assertNotNull(duplicateResponse);
        assertTrue(duplicateResponse.isDuplicateFound());
        assertEquals(1, duplicateResponse.getMatchCount());
        assertEquals("CMP-200", duplicateResponse.getMatches().get(0).getComplaintNumber());
        assertTrue(duplicateResponse.getMatches().get(0).getDistanceMeters() < 30.0);
    }
}
