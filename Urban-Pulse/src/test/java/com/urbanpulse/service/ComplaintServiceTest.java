package com.urbanpulse.service;

import com.urbanpulse.dto.AssignWorkerRequest;
import com.urbanpulse.dto.CheckDuplicateRequest;
import com.urbanpulse.dto.CheckDuplicateResponse;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.DuplicateComplaintSummary;
import com.urbanpulse.dto.ForwardComplaintRequest;
import com.urbanpulse.dto.JoinComplaintResponse;
import com.urbanpulse.dto.UpdateStatusRequest;
import com.urbanpulse.entity.Complaint;
import com.urbanpulse.entity.ComplaintParticipant;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceTest {

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

    @InjectMocks
    private ComplaintService complaintService;

    private User citizen;
    private User citizen2;
    private User admin;
    private User supervisorRoads;
    private User supervisorSanitation;
    private User workerRoads;
    private User workerSanitation;
    private Department roadsDepartment;
    private Department sanitationDepartment;

    @BeforeEach
    void setUp() {
        roadsDepartment = Department.builder().id(101L).name("ROADS").description("Road maintenance").build();
        sanitationDepartment = Department.builder().id(102L).name("SANITATION").description("Waste management").build();

        citizen = User.builder().mobileNumber("+91 9111111111").name("John Citizen").role(Role.CITIZEN).fcmToken("fcm-citizen-token").build();
        citizen2 = User.builder().mobileNumber("+91 9111111122").name("Jane Citizen").role(Role.CITIZEN).fcmToken("fcm-citizen2-token").build();
        admin = User.builder().mobileNumber("+91 9222222222").name("Admin User").role(Role.ADMIN).build();
        supervisorRoads = User.builder().mobileNumber("+91 9333333333").name("Roads Supervisor").role(Role.SUPERVISOR).department(roadsDepartment).build();
        supervisorSanitation = User.builder().mobileNumber("+91 9444444444").name("Sanitation Supervisor").role(Role.SUPERVISOR).department(sanitationDepartment).build();
        workerRoads = User.builder().mobileNumber("+91 9555555555").name("Roads Worker").role(Role.WORKER).department(roadsDepartment).build();
        workerSanitation = User.builder().mobileNumber("+91 9666666666").name("Sanitation Worker").role(Role.WORKER).department(sanitationDepartment).build();
    }

    @Test
    @DisplayName("1. Citizen creates complaint successfully (Initial status = SUBMITTED, SLA assigned, FCM triggered)")
    void testCreateComplaint_Success() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/pothole.jpg")
                .latitude(12.9716)
                .longitude(77.5946)
                .issueType(IssueType.POTHOLE)
                .severity("HIGH")
                .build();

        when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(i -> {
            Complaint c = i.getArgument(0);
            c.setId(1L);
            c.setCreatedAt(LocalDateTime.now());
            return c;
        });

        ComplaintResponse response = complaintService.createComplaint(request, "9111111111");

        assertNotNull(response);
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertEquals(IssueType.POTHOLE, response.getIssueType());
        assertEquals(24, response.getSlaHours());
        assertNotNull(response.getSlaDeadline());
        verify(historyRepository, times(1)).save(any(ComplaintStatusHistory.class));
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), any(), eq(ComplaintStatus.SUBMITTED), isNull());
    }

    @Test
    @DisplayName("2. Creation fails when coordinates are invalid")
    void testCreateComplaint_InvalidCoordinates() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .imageUrl("/uploads/pothole.jpg")
                .latitude(95.0)
                .longitude(77.5946)
                .build();

        when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));

        assertThrows(IllegalArgumentException.class, () ->
                complaintService.createComplaint(request, "9111111111"));
    }

    @Test
    @DisplayName("3. Creation fails when image URL is missing")
    void testCreateComplaint_MissingImage() {
        CreateComplaintRequest request = CreateComplaintRequest.builder()
                .latitude(12.9716)
                .longitude(77.5946)
                .imageUrl("   ")
                .build();

        when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));

        assertThrows(IllegalArgumentException.class, () ->
                complaintService.createComplaint(request, "9111111111"));
    }

    @Test
    @DisplayName("4. Admin forwards complaint (Status -> FORWARDED, preserves original SLA deadline)")
    void testAdminForwardComplaint_PreservesSla() {
        LocalDateTime originalDeadline = LocalDateTime.now().plusHours(20);
        Complaint complaint = Complaint.builder()
                .id(10L)
                .complaintNumber("CMP-101")
                .status(ComplaintStatus.SUBMITTED)
                .slaHours(24)
                .slaDeadline(originalDeadline)
                .citizen(citizen)
                .build();

        ForwardComplaintRequest request = ForwardComplaintRequest.builder().departmentId(101L).build();

        when(complaintRepository.findById(10L)).thenReturn(Optional.of(complaint));
        when(userRepository.findByMobileNumber(admin.getMobileNumber())).thenReturn(Optional.of(admin));
        when(departmentRepository.findById(101L)).thenReturn(Optional.of(roadsDepartment));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(i -> i.getArgument(0));

        ComplaintResponse response = complaintService.forwardComplaint(10L, request, "9222222222");

        assertEquals(ComplaintStatus.FORWARDED, response.getStatus());
        assertEquals("ROADS", response.getDepartmentName());
        assertEquals(originalDeadline, response.getSlaDeadline(), "SLA deadline must remain identical upon forwarding");
        verify(historyRepository, times(1)).save(any(ComplaintStatusHistory.class));
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), eq("CMP-101"), eq(ComplaintStatus.FORWARDED), eq("ROADS"));
    }

    @Test
    @DisplayName("5. Non-admin cannot forward complaint")
    void testForwardComplaint_AccessDeniedForNonAdmin() {
        ForwardComplaintRequest request = ForwardComplaintRequest.builder().departmentId(101L).build();
        when(complaintRepository.findById(10L)).thenReturn(Optional.of(new Complaint()));
        when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                complaintService.forwardComplaint(10L, request, "9111111111"));
    }

    @Test
    @DisplayName("6. Supervisor assigns complaint to worker (Status -> ASSIGNED, preserves SLA deadline)")
    void testSupervisorAssignWorker_Success() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(18);
        Complaint complaint = Complaint.builder()
                .id(10L)
                .complaintNumber("CMP-103")
                .status(ComplaintStatus.FORWARDED)
                .department(roadsDepartment)
                .slaDeadline(deadline)
                .citizen(citizen)
                .build();

        AssignWorkerRequest request = AssignWorkerRequest.builder().workerId("9555555555").build();

        when(complaintRepository.findById(10L)).thenReturn(Optional.of(complaint));
        when(userRepository.findByMobileNumber(supervisorRoads.getMobileNumber())).thenReturn(Optional.of(supervisorRoads));
        when(userRepository.findByMobileNumber(workerRoads.getMobileNumber())).thenReturn(Optional.of(workerRoads));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(i -> i.getArgument(0));

        ComplaintResponse response = complaintService.assignWorker(10L, request, "9333333333");

        assertEquals(ComplaintStatus.ASSIGNED, response.getStatus());
        assertEquals(workerRoads.getMobileNumber(), response.getAssignedWorkerMobile());
        assertEquals(deadline, response.getSlaDeadline(), "SLA deadline must NOT reset upon assignment");
        verify(historyRepository, times(1)).save(any(ComplaintStatusHistory.class));
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), eq("CMP-103"), eq(ComplaintStatus.ASSIGNED), isNull());
    }

    @Test
    @DisplayName("7. Supervisor assignment fails when worker belongs to another department")
    void testSupervisorAssignWorker_MismatchDepartment() {
        Complaint complaint = Complaint.builder()
                .id(10L)
                .status(ComplaintStatus.FORWARDED)
                .department(roadsDepartment)
                .build();

        AssignWorkerRequest request = AssignWorkerRequest.builder().workerId("9666666666").build();

        when(complaintRepository.findById(10L)).thenReturn(Optional.of(complaint));
        when(userRepository.findByMobileNumber(supervisorRoads.getMobileNumber())).thenReturn(Optional.of(supervisorRoads));
        when(userRepository.findByMobileNumber(workerSanitation.getMobileNumber())).thenReturn(Optional.of(workerSanitation));

        assertThrows(IllegalArgumentException.class, () ->
                complaintService.assignWorker(10L, request, "9333333333"));
    }

    @Test
    @DisplayName("8. Worker status transition ASSIGNED -> IN_PROGRESS -> RESOLVED (calculates resolvedWithinSla)")
    void testWorkerStatusTransitions() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(5);
        Complaint complaint = Complaint.builder()
                .id(10L)
                .complaintNumber("CMP-104")
                .status(ComplaintStatus.ASSIGNED)
                .slaDeadline(deadline)
                .assignedWorker(workerRoads)
                .citizen(citizen)
                .proofImageUrl("/uploads/proof.jpg")
                .build();

        when(complaintRepository.findById(10L)).thenReturn(Optional.of(complaint));
        when(userRepository.findByMobileNumber(workerRoads.getMobileNumber())).thenReturn(Optional.of(workerRoads));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(i -> i.getArgument(0));

        // Step 1: ASSIGNED -> IN_PROGRESS
        UpdateStatusRequest inProgressRequest = UpdateStatusRequest.builder().status(ComplaintStatus.IN_PROGRESS).remarks("Starting work").build();
        ComplaintResponse response1 = complaintService.updateWorkerStatus(10L, inProgressRequest, "9555555555");
        assertEquals(ComplaintStatus.IN_PROGRESS, response1.getStatus());
        assertEquals(deadline, response1.getSlaDeadline(), "SLA deadline must NOT reset when worker starts");

        // Step 2: IN_PROGRESS -> RESOLVED
        UpdateStatusRequest resolvedRequest = UpdateStatusRequest.builder().status(ComplaintStatus.RESOLVED).remarks("Fixed pothole").build();
        ComplaintResponse response2 = complaintService.updateWorkerStatus(10L, resolvedRequest, "9555555555");
        assertEquals(ComplaintStatus.RESOLVED, response2.getStatus());
        assertNotNull(response2.getResolvedAt());
        assertTrue(response2.getResolvedWithinSla());
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), eq("CMP-104"), eq(ComplaintStatus.RESOLVED), isNull());
    }

    @Test
    @DisplayName("Test A: Same location - Two POTHOLE complaints at identical coordinates detected as duplicates")
    void testA_SameLocation() {
        Complaint existing = Complaint.builder()
                .id(50L)
                .complaintNumber("CMP-50")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.SUBMITTED)
                .latitude(16.494395)
                .longitude(80.498981)
                .createdAt(LocalDateTime.of(2026, 10, 22, 10, 16, 14))
                .build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(List.of(existing));
        when(complaintParticipantRepository.countByComplaintId(50L)).thenReturn(2L);

        CheckDuplicateRequest request = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse response = complaintService.checkDuplicates(request, "9111111122");

        assertTrue(response.isDuplicateFound());
        assertEquals(1, response.getMatchCount());
        assertEquals(30.0, response.getRadiusMeters());
        DuplicateComplaintSummary match = response.getMatches().get(0);
        assertEquals("CMP-50", match.getComplaintNumber());
        assertEquals(0.0, match.getDistanceMeters());
        assertTrue(match.getEligibleForJoin());
    }

    @Test
    @DisplayName("Test B: Within 30 metres - Two example complaints from bug report matched as duplicates")
    void testB_Within30Metres_ReportedBugCoordinates() {
        // Complaint 2 from bug report: CMP-1792644009739 (Lat: 16.494592, Lon: 80.498782, Created 10:10:09)
        Complaint existingComplaint2 = Complaint.builder()
                .id(200L)
                .complaintNumber("CMP-1792644009739")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.SUBMITTED)
                .latitude(16.494592)
                .longitude(80.498782)
                .imageUrl("/uploads/pothole2.jpg")
                .createdAt(LocalDateTime.of(2026, 10, 22, 10, 10, 9))
                .build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(List.of(existingComplaint2));
        when(complaintParticipantRepository.countByComplaintId(200L)).thenReturn(1L);

        // Citizen reports Complaint 1 at: Lat 16.494395, Lon 80.498981
        CheckDuplicateRequest request = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse response = complaintService.checkDuplicates(request, "9111111122");

        assertTrue(response.isDuplicateFound(), "Complaint 2 must be detected as duplicate within 30m threshold");
        assertEquals(1, response.getMatchCount());
        DuplicateComplaintSummary match = response.getMatches().get(0);
        assertEquals("CMP-1792644009739", match.getComplaintNumber());
        assertTrue(match.getEligibleForJoin());
        assertEquals(ComplaintStatus.SUBMITTED, match.getStatus());
    }

    @Test
    @DisplayName("Test C: Outside 30 metres - Complaint far away is not returned")
    void testC_Outside30Metres() {
        Complaint existingFar = Complaint.builder()
                .id(51L)
                .complaintNumber("CMP-51")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.SUBMITTED)
                .latitude(16.495500)
                .longitude(80.500000) // ~160 meters away
                .build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(List.of(existingFar));

        CheckDuplicateRequest request = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse response = complaintService.checkDuplicates(request, "9111111122");

        assertFalse(response.isDuplicateFound());
        assertEquals(0, response.getMatchCount());
        assertTrue(response.getMatches().isEmpty());
    }

    @Test
    @DisplayName("Test D: Active versus resolved - Active complaint appears first even if resolved complaint is closer")
    void testD_ActiveVersusResolved_ActiveFirst() {
        // Resolved complaint: 10 meters away
        Complaint resolvedClose = Complaint.builder()
                .id(301L)
                .complaintNumber("CMP-RESOLVED-1")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.RESOLVED)
                .latitude(16.494450)
                .longitude(80.498950)
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        // Active complaint: 22 meters away
        Complaint activeFurther = Complaint.builder()
                .id(302L)
                .complaintNumber("CMP-ACTIVE-2")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.SUBMITTED)
                .latitude(16.494550)
                .longitude(80.498850)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE))
                .thenReturn(List.of(activeFurther, resolvedClose));

        CheckDuplicateRequest request = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse response = complaintService.checkDuplicates(request, "9111111122");

        assertTrue(response.isDuplicateFound());
        assertEquals(2, response.getMatchCount());
        // Primary first match MUST be the active complaint!
        assertEquals("CMP-ACTIVE-2", response.getMatches().get(0).getComplaintNumber());
        assertEquals(ComplaintStatus.SUBMITTED, response.getMatches().get(0).getStatus());
        assertTrue(response.getMatches().get(0).getEligibleForJoin());

        // Resolved complaint appears after active complaint
        assertEquals("CMP-RESOLVED-1", response.getMatches().get(1).getComplaintNumber());
        assertEquals(ComplaintStatus.RESOLVED, response.getMatches().get(1).getStatus());
        assertFalse(response.getMatches().get(1).getEligibleForJoin());
    }

    @Test
    @DisplayName("Test E: Both complaints active - Symmetric mutual matching for Complaint 1 and Complaint 2")
    void testE_BothComplaintsActive_SymmetricMatching() {
        Complaint complaint1 = Complaint.builder()
                .id(101L)
                .complaintNumber("CMP-1792644374081")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.SUBMITTED)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        Complaint complaint2 = Complaint.builder()
                .id(102L)
                .complaintNumber("CMP-1792644009739")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.SUBMITTED)
                .latitude(16.494592)
                .longitude(80.498782)
                .build();

        // Checking from Complaint 1 location matches Complaint 2
        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(List.of(complaint2));
        CheckDuplicateRequest req1 = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();
        CheckDuplicateResponse resp1 = complaintService.checkDuplicates(req1, "9111111122");
        assertTrue(resp1.isDuplicateFound());
        assertEquals("CMP-1792644009739", resp1.getMatches().get(0).getComplaintNumber());

        // Checking from Complaint 2 location matches Complaint 1
        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(List.of(complaint1));
        CheckDuplicateRequest req2 = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494592)
                .longitude(80.498782)
                .build();
        CheckDuplicateResponse resp2 = complaintService.checkDuplicates(req2, "9111111122");
        assertTrue(resp2.isDuplicateFound());
        assertEquals("CMP-1792644374081", resp2.getMatches().get(0).getComplaintNumber());
    }

    @Test
    @DisplayName("Test G: Multiple active matches - Ordered by distance (nearest first)")
    void testG_MultipleActiveMatches_OrderedByDistance() {
        Complaint c15m = Complaint.builder()
                .id(1L).complaintNumber("CMP-15M")
                .issueType(IssueType.POTHOLE).status(ComplaintStatus.SUBMITTED)
                .latitude(16.494500).longitude(80.498981).build();

        Complaint c5m = Complaint.builder()
                .id(2L).complaintNumber("CMP-5M")
                .issueType(IssueType.POTHOLE).status(ComplaintStatus.SUBMITTED)
                .latitude(16.494430).longitude(80.498981).build();

        Complaint c25m = Complaint.builder()
                .id(3L).complaintNumber("CMP-25M")
                .issueType(IssueType.POTHOLE).status(ComplaintStatus.SUBMITTED)
                .latitude(16.494600).longitude(80.498981).build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE))
                .thenReturn(List.of(c15m, c5m, c25m));

        CheckDuplicateRequest req = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse resp = complaintService.checkDuplicates(req, "9111111122");

        assertEquals(3, resp.getMatchCount());
        assertEquals("CMP-5M", resp.getMatches().get(0).getComplaintNumber());
        assertEquals("CMP-15M", resp.getMatches().get(1).getComplaintNumber());
        assertEquals("CMP-25M", resp.getMatches().get(2).getComplaintNumber());
        assertTrue(resp.getMatches().get(0).getDistanceMeters() < resp.getMatches().get(1).getDistanceMeters());
        assertTrue(resp.getMatches().get(1).getDistanceMeters() < resp.getMatches().get(2).getDistanceMeters());
    }

    @Test
    @DisplayName("Test H: Different issue categories - Pothole does not match Garbage at same location")
    void testH_DifferentIssueCategories() {
        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE)).thenReturn(Collections.emptyList());

        CheckDuplicateRequest req = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse resp = complaintService.checkDuplicates(req, "9111111122");

        assertFalse(resp.isDuplicateFound());
        assertEquals(0, resp.getMatchCount());
    }

    @Test
    @DisplayName("Test I: No active matches - Resolved complaint returned with eligibleForJoin=false")
    void testI_NoActiveMatches_OnlyResolved() {
        Complaint resolvedOnly = Complaint.builder()
                .id(400L)
                .complaintNumber("CMP-RESOLVED-ONLY")
                .issueType(IssueType.POTHOLE)
                .status(ComplaintStatus.RESOLVED)
                .latitude(16.494430)
                .longitude(80.498981)
                .build();

        when(complaintRepository.findByIssueTypeOrderByCreatedAtDesc(IssueType.POTHOLE))
                .thenReturn(List.of(resolvedOnly));

        CheckDuplicateRequest req = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.494395)
                .longitude(80.498981)
                .build();

        CheckDuplicateResponse resp = complaintService.checkDuplicates(req, "9111111122");

        assertTrue(resp.isDuplicateFound());
        assertEquals(1, resp.getMatchCount());
        assertFalse(resp.getMatches().get(0).getEligibleForJoin());
        assertEquals(ComplaintStatus.RESOLVED, resp.getMatches().get(0).getStatus());
    }

    @Test
    @DisplayName("Test F: Join existing complaint - Participant count increases without creating another complaint")
    void testF_JoinComplaint_Success() {
        Complaint existing = Complaint.builder()
                .id(60L)
                .complaintNumber("CMP-60")
                .status(ComplaintStatus.SUBMITTED)
                .citizen(citizen)
                .build();

        when(userRepository.findByMobileNumber(citizen2.getMobileNumber())).thenReturn(Optional.of(citizen2));
        when(complaintRepository.findById(60L)).thenReturn(Optional.of(existing));
        when(complaintParticipantRepository.findByComplaintIdAndCitizenMobileNumber(60L, citizen2.getMobileNumber()))
                .thenReturn(Optional.empty());
        when(complaintParticipantRepository.save(any(ComplaintParticipant.class))).thenAnswer(i -> {
            ComplaintParticipant cp = i.getArgument(0);
            cp.setId(100L);
            cp.setJoinedAt(LocalDateTime.now());
            return cp;
        });
        when(complaintParticipantRepository.countByComplaintId(60L)).thenReturn(1L);

        JoinComplaintResponse response = complaintService.joinComplaint(60L, "9111111122");

        assertNotNull(response);
        assertEquals(60L, response.getComplaintId());
        assertEquals("CMP-60", response.getComplaintNumber());
        assertEquals(1L, response.getJoinCount());
        assertFalse(response.isAlreadyJoined());
        assertFalse(response.isOriginalReporter());
        verify(complaintParticipantRepository, times(1)).save(any(ComplaintParticipant.class));
    }

    @Test
    @DisplayName("12. Citizen already joined is handled idempotently without creating duplicate participant")
    void testJoinComplaint_AlreadyJoined() {
        Complaint existing = Complaint.builder()
                .id(60L)
                .complaintNumber("CMP-60")
                .citizen(citizen)
                .build();

        ComplaintParticipant existingParticipant = ComplaintParticipant.builder()
                .id(100L)
                .complaint(existing)
                .citizen(citizen2)
                .joinedAt(LocalDateTime.now().minusHours(2))
                .build();

        when(userRepository.findByMobileNumber(citizen2.getMobileNumber())).thenReturn(Optional.of(citizen2));
        when(complaintRepository.findById(60L)).thenReturn(Optional.of(existing));
        when(complaintParticipantRepository.countByComplaintId(60L)).thenReturn(1L);
        when(complaintParticipantRepository.findByComplaintIdAndCitizenMobileNumber(60L, citizen2.getMobileNumber()))
                .thenReturn(Optional.of(existingParticipant));

        JoinComplaintResponse response = complaintService.joinComplaint(60L, "9111111122");

        assertTrue(response.isAlreadyJoined());
        assertFalse(response.isOriginalReporter());
        assertEquals(1L, response.getJoinCount());
        verify(complaintParticipantRepository, never()).save(any(ComplaintParticipant.class));
    }

    @Test
    @DisplayName("13. Original reporter cannot join their own complaint as participant")
    void testJoinComplaint_OriginalReporter() {
        Complaint existing = Complaint.builder()
                .id(60L)
                .complaintNumber("CMP-60")
                .citizen(citizen)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();

        when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));
        when(complaintRepository.findById(60L)).thenReturn(Optional.of(existing));
        when(complaintParticipantRepository.countByComplaintId(60L)).thenReturn(0L);

        JoinComplaintResponse response = complaintService.joinComplaint(60L, "9111111111");

        assertTrue(response.isAlreadyJoined());
        assertTrue(response.isOriginalReporter());
        verify(complaintParticipantRepository, never()).save(any(ComplaintParticipant.class));
    }

    @Test
    @DisplayName("14. Status update notifies both original reporter and joined citizens")
    void testStatusUpdate_NotifiesAllParticipants() {
        Complaint complaint = Complaint.builder()
                .id(70L)
                .complaintNumber("CMP-70")
                .status(ComplaintStatus.FORWARDED)
                .department(roadsDepartment)
                .citizen(citizen)
                .build();

        ComplaintParticipant participant = ComplaintParticipant.builder()
                .id(101L)
                .complaint(complaint)
                .citizen(citizen2)
                .build();

        AssignWorkerRequest request = AssignWorkerRequest.builder().workerId("9555555555").build();

        when(complaintRepository.findById(70L)).thenReturn(Optional.of(complaint));
        when(userRepository.findByMobileNumber(supervisorRoads.getMobileNumber())).thenReturn(Optional.of(supervisorRoads));
        when(userRepository.findByMobileNumber(workerRoads.getMobileNumber())).thenReturn(Optional.of(workerRoads));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(i -> i.getArgument(0));
        when(complaintParticipantRepository.findByComplaintId(70L)).thenReturn(List.of(participant));

        complaintService.assignWorker(70L, request, "9333333333");

        // Notification must be sent to original reporter (citizen) AND joined participant (citizen2)
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), eq("CMP-70"), eq(ComplaintStatus.ASSIGNED), isNull());
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen2-token"), eq("CMP-70"), eq(ComplaintStatus.ASSIGNED), isNull());
    }
}
