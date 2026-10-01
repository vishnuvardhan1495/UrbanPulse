package com.urbanpulse.service;

import com.urbanpulse.dto.AssignWorkerRequest;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.ForwardComplaintRequest;
import com.urbanpulse.dto.StatusHistoryResponse;
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
import com.urbanpulse.util.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ComplaintService {

    private static final Logger logger = LoggerFactory.getLogger(ComplaintService.class);

    private final ComplaintRepository complaintRepository;
    private final ComplaintStatusHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final FileStorageService fileStorageService;
    private final SlaService slaService;
    private final NotificationService notificationService;

    public ComplaintService(ComplaintRepository complaintRepository,
                            ComplaintStatusHistoryRepository historyRepository,
                            UserRepository userRepository,
                            DepartmentRepository departmentRepository,
                            FileStorageService fileStorageService,
                            SlaService slaService,
                            NotificationService notificationService) {
        this.complaintRepository = complaintRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.fileStorageService = fileStorageService;
        this.slaService = slaService;
        this.notificationService = notificationService;
    }

    /**
     * Safe startup backfill: Computes SLA fields for legacy complaints missing SLA data,
     * strictly preserving the original created_at timestamps.
     * For existing RESOLVED complaints without resolved_at, historical fallback uses updated_at.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillLegacyComplaints() {
        try {
            List<Complaint> allComplaints = complaintRepository.findAll();
            int backfilledCount = 0;

            for (Complaint c : allComplaints) {
                boolean modified = false;

                if (c.getSlaDeadline() == null || c.getSlaHours() == null) {
                    int hours = slaService.getSlaHours(c.getIssueType());
                    LocalDateTime baseTime = c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.now();
                    c.setSlaHours(hours);
                    c.setSlaDeadline(slaService.calculateDeadline(baseTime, c.getIssueType()));
                    modified = true;
                }

                if (c.getStatus() == ComplaintStatus.RESOLVED && c.getResolvedAt() == null) {
                    // Historical backfill approximation for existing resolved complaints
                    LocalDateTime fallbackResolvedAt = c.getUpdatedAt() != null ? c.getUpdatedAt()
                            : (c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.now());
                    c.setResolvedAt(fallbackResolvedAt);
                    c.setResolvedWithinSla(c.getSlaDeadline() != null && !fallbackResolvedAt.isAfter(c.getSlaDeadline()));
                    modified = true;
                }

                if (modified) {
                    complaintRepository.save(c);
                    backfilledCount++;
                }
            }

            if (backfilledCount > 0) {
                logger.info("[SLA-BACKFILL] Safely initialized SLA fields for {} legacy complaints.", backfilledCount);
            }
        } catch (Exception ex) {
            logger.warn("[SLA-BACKFILL] Legacy backfill completed with note: {}", ex.getMessage());
        }
    }

    @Transactional
    public ComplaintResponse createComplaint(CreateComplaintRequest request, String citizenMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(citizenMobile);
        User citizen = userRepository.findByMobileNumber(normalizedMobile)
                .orElseThrow(() -> new IllegalArgumentException("Citizen user not found: " + normalizedMobile));

        validateCoordinates(request.getLatitude(), request.getLongitude());

        if (request.getImageUrl() == null || request.getImageUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("Complaint image is required.");
        }

        String complaintNumber = generateUniqueComplaintNumber();
        IssueType issueType = request.getIssueType() != null ? request.getIssueType() : IssueType.NONE;

        LocalDateTime now = LocalDateTime.now();
        int slaHours = slaService.getSlaHours(issueType);
        LocalDateTime slaDeadline = slaService.calculateDeadline(now, issueType);

        Complaint complaint = Complaint.builder()
                .complaintNumber(complaintNumber)
                .issueType(issueType)
                .status(ComplaintStatus.SUBMITTED)
                .severity(request.getSeverity() != null ? request.getSeverity() : "MEDIUM")
                .priority("MEDIUM")
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .imageUrl(request.getImageUrl())
                .slaHours(slaHours)
                .slaDeadline(slaDeadline)
                .resolvedAt(null)
                .resolvedWithinSla(null)
                .citizen(citizen)
                .department(null)
                .assignedWorker(null)
                .build();

        Complaint savedComplaint = complaintRepository.save(complaint);

        recordStatusHistory(savedComplaint, null, ComplaintStatus.SUBMITTED, citizen, "Complaint submitted by citizen");

        // Send non-blocking, failure-isolated FCM notification to citizen
        notificationService.sendComplaintStatusNotification(
                citizen.getFcmToken(),
                savedComplaint.getComplaintNumber(),
                ComplaintStatus.SUBMITTED,
                null
        );

        logger.info("Complaint created successfully with number: {} and SLA deadline: {}", complaintNumber, slaDeadline);
        return mapToResponse(savedComplaint);
    }

    public List<ComplaintResponse> getCitizenComplaints(String citizenMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(citizenMobile);
        return complaintRepository.findByCitizenMobileNumberOrderByCreatedAtDesc(normalizedMobile)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ComplaintResponse getComplaintById(Long id, String currentUserMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(currentUserMobile);
        Complaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + id));

        User currentUser = userRepository.findByMobileNumber(normalizedMobile)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + normalizedMobile));

        validateViewPermission(complaint, currentUser);

        return mapToResponse(complaint);
    }

    public List<ComplaintResponse> getAdminComplaints() {
        return complaintRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ComplaintResponse forwardComplaint(Long complaintId, ForwardComplaintRequest request, String adminMobile) {
        String normalizedAdminMobile = PhoneNumberUtil.normalize(adminMobile);
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        User admin = userRepository.findByMobileNumber(normalizedAdminMobile)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + normalizedAdminMobile));

        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only ADMIN can forward complaints.");
        }

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + request.getDepartmentId()));

        ComplaintStatus oldStatus = complaint.getStatus();
        complaint.setDepartment(department);
        complaint.setStatus(ComplaintStatus.FORWARDED);
        // SLA MUST NOT RESET: original slaHours and slaDeadline remain intact

        Complaint updated = complaintRepository.save(complaint);

        recordStatusHistory(updated, oldStatus, ComplaintStatus.FORWARDED, admin, "Forwarded to department: " + department.getName());

        // Send non-blocking FCM push notification to citizen
        if (updated.getCitizen() != null) {
            notificationService.sendComplaintStatusNotification(
                    updated.getCitizen().getFcmToken(),
                    updated.getComplaintNumber(),
                    ComplaintStatus.FORWARDED,
                    department.getName()
            );
        }

        logger.info("Complaint {} forwarded to department {}", complaint.getComplaintNumber(), department.getName());
        return mapToResponse(updated);
    }

    public List<ComplaintResponse> getSupervisorComplaints(String supervisorMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(supervisorMobile);
        User supervisor = userRepository.findByMobileNumber(normalizedMobile)
                .orElseThrow(() -> new IllegalArgumentException("Supervisor not found: " + normalizedMobile));

        if (supervisor.getRole() != Role.SUPERVISOR) {
            throw new AccessDeniedException("User is not a SUPERVISOR.");
        }

        if (supervisor.getDepartment() == null) {
            throw new IllegalStateException("Supervisor is not assigned to any department.");
        }

        return complaintRepository.findByDepartmentIdOrderByCreatedAtDesc(supervisor.getDepartment().getId())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ComplaintResponse assignWorker(Long complaintId, AssignWorkerRequest request, String supervisorMobile) {
        String normalizedSupervisorMobile = PhoneNumberUtil.normalize(supervisorMobile);
        String normalizedWorkerId = PhoneNumberUtil.normalize(request.getWorkerId());

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        User supervisor = userRepository.findByMobileNumber(normalizedSupervisorMobile)
                .orElseThrow(() -> new IllegalArgumentException("Supervisor not found: " + normalizedSupervisorMobile));

        if (supervisor.getRole() != Role.SUPERVISOR) {
            throw new AccessDeniedException("Only SUPERVISOR can assign workers.");
        }

        if (supervisor.getDepartment() == null || complaint.getDepartment() == null
                || !supervisor.getDepartment().getId().equals(complaint.getDepartment().getId())) {
            throw new AccessDeniedException("Supervisor cannot assign worker to complaint outside their department.");
        }

        User worker = userRepository.findByMobileNumber(normalizedWorkerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found with ID: " + normalizedWorkerId));

        if (worker.getRole() != Role.WORKER) {
            throw new IllegalArgumentException("Assigned user must have WORKER role.");
        }

        if (worker.getDepartment() == null || !worker.getDepartment().getId().equals(supervisor.getDepartment().getId())) {
            throw new IllegalArgumentException("Worker does not belong to the supervisor's department.");
        }

        ComplaintStatus oldStatus = complaint.getStatus();
        complaint.setAssignedWorker(worker);
        complaint.setStatus(ComplaintStatus.ASSIGNED);
        // SLA MUST NOT RESET: original slaHours and slaDeadline remain intact

        Complaint updated = complaintRepository.save(complaint);

        recordStatusHistory(updated, oldStatus, ComplaintStatus.ASSIGNED, supervisor, "Assigned to worker: " + worker.getMobileNumber());

        // Send non-blocking FCM push notification to citizen
        if (updated.getCitizen() != null) {
            notificationService.sendComplaintStatusNotification(
                    updated.getCitizen().getFcmToken(),
                    updated.getComplaintNumber(),
                    ComplaintStatus.ASSIGNED,
                    null
            );
        }

        logger.info("Complaint {} assigned to worker {}", complaint.getComplaintNumber(), worker.getMobileNumber());
        return mapToResponse(updated);
    }

    public List<ComplaintResponse> getWorkerComplaints(String workerMobile) {
        String normalizedWorkerMobile = PhoneNumberUtil.normalize(workerMobile);
        User worker = userRepository.findByMobileNumber(normalizedWorkerMobile)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + normalizedWorkerMobile));

        if (worker.getRole() != Role.WORKER) {
            throw new AccessDeniedException("User is not a WORKER.");
        }

        return complaintRepository.findByAssignedWorkerMobileNumberOrderByCreatedAtDesc(normalizedWorkerMobile)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ComplaintResponse updateWorkerStatus(Long complaintId, UpdateStatusRequest request, String workerMobile) {
        String normalizedWorkerMobile = PhoneNumberUtil.normalize(workerMobile);
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        User worker = userRepository.findByMobileNumber(normalizedWorkerMobile)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + normalizedWorkerMobile));

        if (worker.getRole() != Role.WORKER) {
            throw new AccessDeniedException("Only WORKER can update assigned complaint status.");
        }

        if (complaint.getAssignedWorker() == null || !complaint.getAssignedWorker().getMobileNumber().equals(normalizedWorkerMobile)) {
            throw new AccessDeniedException("Worker cannot update status for a complaint assigned to another user.");
        }

        ComplaintStatus oldStatus = complaint.getStatus();
        ComplaintStatus newStatus = request.getStatus();

        validateStatusTransition(oldStatus, newStatus);

        if (newStatus == ComplaintStatus.RESOLVED) {
            if (complaint.getProofImageUrl() == null || complaint.getProofImageUrl().trim().isEmpty()) {
                throw new IllegalArgumentException("Proof image must be uploaded before resolving the complaint.");
            }
            LocalDateTime resolvedAt = LocalDateTime.now();
            complaint.setResolvedAt(resolvedAt);
            complaint.setResolvedWithinSla(complaint.getSlaDeadline() != null && !resolvedAt.isAfter(complaint.getSlaDeadline()));
        }

        // SLA deadline is NEVER reset
        complaint.setStatus(newStatus);
        Complaint updated = complaintRepository.save(complaint);

        recordStatusHistory(updated, oldStatus, newStatus, worker, request.getRemarks());

        // Send non-blocking FCM push notification to citizen
        if (updated.getCitizen() != null) {
            notificationService.sendComplaintStatusNotification(
                    updated.getCitizen().getFcmToken(),
                    updated.getComplaintNumber(),
                    newStatus,
                    null
            );
        }

        logger.info("Complaint {} status updated from {} to {} by worker {}",
                complaint.getComplaintNumber(), oldStatus, newStatus, normalizedWorkerMobile);
        return mapToResponse(updated);
    }

    @Transactional
    public ComplaintResponse uploadProofImage(Long complaintId, MultipartFile file, String workerMobile) {
        String normalizedWorkerMobile = PhoneNumberUtil.normalize(workerMobile);
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        User worker = userRepository.findByMobileNumber(normalizedWorkerMobile)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + normalizedWorkerMobile));

        if (worker.getRole() != Role.WORKER) {
            throw new AccessDeniedException("Only WORKER can upload proof images.");
        }

        if (complaint.getAssignedWorker() == null || !complaint.getAssignedWorker().getMobileNumber().equals(normalizedWorkerMobile)) {
            throw new AccessDeniedException("Worker can only upload proof for complaints assigned to them.");
        }

        String proofUrl = fileStorageService.storeFile(file);
        complaint.setProofImageUrl(proofUrl);

        Complaint updated = complaintRepository.save(complaint);
        logger.info("Proof image uploaded for complaint {}", complaint.getComplaintNumber());

        return mapToResponse(updated);
    }

    public String uploadImage(MultipartFile file) {
        return fileStorageService.storeFile(file);
    }

    private void validateCoordinates(Double lat, Double lon) {
        if (lat == null || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Invalid latitude. Must be between -90 and 90.");
        }
        if (lon == null || lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("Invalid longitude. Must be between -180 and 180.");
        }
    }

    private void validateStatusTransition(ComplaintStatus current, ComplaintStatus next) {
        if (current == next) {
            return;
        }

        if (current == ComplaintStatus.ASSIGNED && (next == ComplaintStatus.IN_PROGRESS || next == ComplaintStatus.REJECTED)) {
            return;
        }

        if (current == ComplaintStatus.IN_PROGRESS && (next == ComplaintStatus.RESOLVED || next == ComplaintStatus.REJECTED)) {
            return;
        }

        throw new IllegalArgumentException("Invalid status transition from " + current + " to " + next);
    }

    private void validateViewPermission(Complaint complaint, User user) {
        if (user.getRole() == Role.ADMIN) {
            return;
        }

        if (user.getRole() == Role.SUPERVISOR) {
            if (user.getDepartment() != null && complaint.getDepartment() != null
                    && user.getDepartment().getId().equals(complaint.getDepartment().getId())) {
                return;
            }
        }

        if (user.getRole() == Role.CITIZEN) {
            if (complaint.getCitizen() != null && complaint.getCitizen().getMobileNumber().equals(user.getMobileNumber())) {
                return;
            }
        }

        if (user.getRole() == Role.WORKER) {
            if (complaint.getAssignedWorker() != null && complaint.getAssignedWorker().getMobileNumber().equals(user.getMobileNumber())) {
                return;
            }
        }

        throw new AccessDeniedException("You do not have permission to view this complaint.");
    }

    private void recordStatusHistory(Complaint complaint, ComplaintStatus oldStatus, ComplaintStatus newStatus, User changedBy, String remarks) {
        ComplaintStatusHistory history = ComplaintStatusHistory.builder()
                .complaint(complaint)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(changedBy)
                .remarks(remarks)
                .build();
        historyRepository.save(history);
    }

    private ComplaintResponse mapToResponse(Complaint c) {
        List<ComplaintStatusHistory> rawHistories = historyRepository.findByComplaintIdOrderByChangedAtAsc(c.getId());

        List<StatusHistoryResponse> historyResponses = rawHistories.stream()
                .map(h -> StatusHistoryResponse.builder()
                        .id(h.getId())
                        .oldStatus(h.getOldStatus())
                        .newStatus(h.getNewStatus())
                        .changedByMobile(h.getChangedBy() != null ? h.getChangedBy().getMobileNumber() : null)
                        .changedByName(h.getChangedBy() != null ? h.getChangedBy().getName() : null)
                        .changedAt(h.getChangedAt())
                        .remarks(h.getRemarks())
                        .build())
                .collect(Collectors.toList());

        // Derive supervisor from the ASSIGNED step if available
        String supervisorMobile = null;
        String supervisorName = null;
        for (ComplaintStatusHistory h : rawHistories) {
            if (h.getNewStatus() == ComplaintStatus.ASSIGNED && h.getChangedBy() != null) {
                supervisorMobile = h.getChangedBy().getMobileNumber();
                supervisorName = h.getChangedBy().getName();
                break;
            }
        }

        return ComplaintResponse.builder()
                .id(c.getId())
                .complaintNumber(c.getComplaintNumber())
                .issueType(c.getIssueType())
                .status(c.getStatus())
                .severity(c.getSeverity())
                .priority(c.getPriority())
                .latitude(c.getLatitude())
                .longitude(c.getLongitude())
                .imageUrl(c.getImageUrl())
                .proofImageUrl(c.getProofImageUrl())
                .citizenMobile(c.getCitizen() != null ? c.getCitizen().getMobileNumber() : null)
                .citizenName(c.getCitizen() != null ? c.getCitizen().getName() : null)
                .departmentId(c.getDepartment() != null ? c.getDepartment().getId() : null)
                .departmentName(c.getDepartment() != null ? c.getDepartment().getName() : null)
                .supervisorMobile(supervisorMobile)
                .supervisorName(supervisorName)
                .assignedWorkerMobile(c.getAssignedWorker() != null ? c.getAssignedWorker().getMobileNumber() : null)
                .assignedWorkerName(c.getAssignedWorker() != null ? c.getAssignedWorker().getName() : null)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .slaHours(c.getSlaHours())
                .slaDeadline(c.getSlaDeadline())
                .slaStatus(slaService.getSlaStatus(c))
                .remainingMinutes(slaService.getRemainingMinutes(c))
                .resolvedAt(c.getResolvedAt())
                .resolvedWithinSla(c.getResolvedWithinSla())
                .statusHistory(historyResponses)
                .build();
    }

    private String generateUniqueComplaintNumber() {
        return "CMP-" + System.currentTimeMillis();
    }
}
