package com.urbanpulse.service;

import com.urbanpulse.dto.AssignWorkerRequest;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.ForwardComplaintRequest;
import com.urbanpulse.dto.UpdateStatusRequest;
import com.urbanpulse.entity.Complaint;
import com.urbanpulse.entity.ComplaintStatus;
import com.urbanpulse.entity.ComplaintStatusHistory;
import com.urbanpulse.entity.Department;
import com.urbanpulse.entity.IssueType;
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
            return c;
        });

        ComplaintResponse response = complaintService.createComplaint(request, "9111111111");

        assertNotNull(response);
        assertEquals(ComplaintStatus.SUBMITTED, response.getStatus());
        assertTrue(response.getComplaintNumber().startsWith("CMP-"));
        assertEquals(12.9716, response.getLatitude());
        assertEquals(77.5946, response.getLongitude());
        assertEquals(24, response.getSlaHours());
        assertNotNull(response.getSlaDeadline());
        verify(historyRepository, times(1)).save(any(ComplaintStatusHistory.class));
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), anyString(), eq(ComplaintStatus.SUBMITTED), isNull());
    }

    @Test
    @DisplayName("2. GPS coordinate validation fails for invalid latitude/longitude")
    void testCreateComplaint_InvalidGPS() {
        CreateComplaintRequest invalidLatRequest = CreateComplaintRequest.builder()
                .imageUrl("/uploads/test.jpg")
                .latitude(100.0)
                .longitude(77.5946)
                .build();

        when(userRepository.findByMobileNumber(citizen.getMobileNumber())).thenReturn(Optional.of(citizen));

        assertThrows(IllegalArgumentException.class, () ->
                complaintService.createComplaint(invalidLatRequest, "9111111111"));
    }

    @Test
    @DisplayName("3. Citizen sees own complaints only")
    void testGetCitizenComplaints() {
        Complaint c1 = Complaint.builder().id(1L).complaintNumber("CMP-100").status(ComplaintStatus.SUBMITTED).citizen(citizen).build();
        when(complaintRepository.findByCitizenMobileNumberOrderByCreatedAtDesc(citizen.getMobileNumber())).thenReturn(List.of(c1));

        List<ComplaintResponse> list = complaintService.getCitizenComplaints("9111111111");
        assertEquals(1, list.size());
        assertEquals("CMP-100", list.get(0).getComplaintNumber());
    }

    @Test
    @DisplayName("4. Admin forwards complaint to department (SLA deadline NOT reset, FCM triggered)")
    void testAdminForwardComplaint_Success() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(20);
        Complaint complaint = Complaint.builder()
                .id(10L)
                .complaintNumber("CMP-101")
                .status(ComplaintStatus.SUBMITTED)
                .slaHours(24)
                .slaDeadline(deadline)
                .citizen(citizen)
                .build();

        ForwardComplaintRequest request = ForwardComplaintRequest.builder().departmentId(101L).build();

        when(complaintRepository.findById(10L)).thenReturn(Optional.of(complaint));
        when(userRepository.findByMobileNumber(admin.getMobileNumber())).thenReturn(Optional.of(admin));
        when(departmentRepository.findById(101L)).thenReturn(Optional.of(roadsDepartment));
        when(complaintRepository.save(any(Complaint.class))).thenAnswer(i -> i.getArgument(0));

        ComplaintResponse response = complaintService.forwardComplaint(10L, request, "9222222222");

        assertEquals(ComplaintStatus.FORWARDED, response.getStatus());
        assertEquals(101L, response.getDepartmentId());
        assertEquals("ROADS", response.getDepartmentName());
        assertEquals(deadline, response.getSlaDeadline(), "SLA deadline must NOT reset upon forwarding");
        verify(historyRepository, times(1)).save(any(ComplaintStatusHistory.class));
        verify(notificationService, times(1)).sendComplaintStatusNotification(eq("fcm-citizen-token"), eq("CMP-101"), eq(ComplaintStatus.FORWARDED), eq("ROADS"));
    }

    @Test
    @DisplayName("5. Supervisor sees own department complaints and cannot see other department complaints")
    void testSupervisorGetComplaints() {
        Complaint roadsComplaint = Complaint.builder().id(10L).complaintNumber("CMP-102").status(ComplaintStatus.FORWARDED).department(roadsDepartment).build();
        when(userRepository.findByMobileNumber(supervisorRoads.getMobileNumber())).thenReturn(Optional.of(supervisorRoads));
        when(complaintRepository.findByDepartmentIdOrderByCreatedAtDesc(101L)).thenReturn(List.of(roadsComplaint));

        List<ComplaintResponse> list = complaintService.getSupervisorComplaints("9333333333");
        assertEquals(1, list.size());
        assertEquals("ROADS", list.get(0).getDepartmentName());
    }

    @Test
    @DisplayName("6. Supervisor assigns worker belonging to department (SLA deadline NOT reset)")
    void testSupervisorAssignWorker_Success() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(18);
        Complaint complaint = Complaint.builder()
                .id(10L)
                .complaintNumber("CMP-103")
                .status(ComplaintStatus.FORWARDED)
                .slaHours(24)
                .slaDeadline(deadline)
                .citizen(citizen)
                .department(roadsDepartment)
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
}
