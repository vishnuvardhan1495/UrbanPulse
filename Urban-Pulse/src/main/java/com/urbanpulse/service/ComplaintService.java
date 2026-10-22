package com.urbanpulse.service;

import com.urbanpulse.dto.AssignWorkerRequest;
import com.urbanpulse.dto.CheckDuplicateRequest;
import com.urbanpulse.dto.CheckDuplicateResponse;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.DuplicateComplaintSummary;
import com.urbanpulse.dto.ForwardComplaintRequest;
import com.urbanpulse.dto.JoinComplaintResponse;
import com.urbanpulse.dto.StatusHistoryResponse;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ComplaintService {

    private static final Logger logger = LoggerFactory.getLogger(ComplaintService.class);

    public static final double DEFAULT_DUPLICATE_RADIUS_METERS = 30.0;
    public static final double GPS_TOLERANCE_METERS = 1.0;

    public static final Set<ComplaintStatus> ACTIVE_STATUSES = Set.of(
            ComplaintStatus.SUBMITTED,
            ComplaintStatus.FORWARDED,
            ComplaintStatus.ASSIGNED,
            ComplaintStatus.IN_PROGRESS
    );

    private final ComplaintRepository complaintRepository;
    private final ComplaintParticipantRepository complaintParticipantRepository;
    private final ComplaintStatusHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final FileStorageService fileStorageService;
    private final SlaService slaService;
    private final NotificationService notificationService;

    public ComplaintService(ComplaintRepository complaintRepository,
                            ComplaintParticipantRepository complaintParticipantRepository,
                            ComplaintStatusHistoryRepository historyRepository,
                            UserRepository userRepository,
                            DepartmentRepository departmentRepository,
                            FileStorageService fileStorageService,
                            SlaService slaService,
                            NotificationService notificationService) {
        this.complaintRepository = complaintRepository;
        this.complaintParticipantRepository = complaintParticipantRepository;
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

    /**
     * Calculates precise geographical distance in meters using the Haversine formula.
     * Earth radius R = 6,371,000 meters.
     */
    public static double calculateHaversineDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0; // Earth's mean radius in meters
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2.0) * Math.sin(deltaPhi / 2.0)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2.0) * Math.sin(deltaLambda / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        double distance = R * c;
        return Math.round(distance * 10.0) / 10.0;
    }

    /**
     * Searches for existing complaints matching the same issue category within a configurable radius (default 30m).
     * Prioritizes active complaints (SUBMITTED, FORWARDED, ASSIGNED, IN_PROGRESS) over resolved complaints,
     * sorting by shortest distance first.
     */
    public CheckDuplicateResponse checkDuplicates(CheckDuplicateRequest request, String currentUserMobile) {
        if (request == null) {
            throw new IllegalArgumentException("Request body cannot be null.");
        }
        if (request.getIssueType() == null) {
            throw new IllegalArgumentException("Issue category is required for duplicate checking.");
        }
        validateCoordinates(request.getLatitude(), request.getLongitude());

        if (request.getRadiusMeters() != null && request.getRadiusMeters() < 0) {
            throw new IllegalArgumentException("Search radius cannot be negative.");
        }

        double radius = (request.getRadiusMeters() != null && request.getRadiusMeters() > 0)
                ? request.getRadiusMeters()
                : DEFAULT_DUPLICATE_RADIUS_METERS;

        String normalizedUser = currentUserMobile != null ? PhoneNumberUtil.normalize(currentUserMobile) : null;

        List<Complaint> candidates = complaintRepository.findByIssueTypeOrderByCreatedAtDesc(request.getIssueType());
        List<DuplicateComplaintSummary> matches = new ArrayList<>();

        // Real-world mobile GPS jitter margin allows boundary complaints (such as 30.5m) to be matched
        double effectiveMaxDistance = radius + GPS_TOLERANCE_METERS;

        for (Complaint candidate : candidates) {
            if (candidate.getLatitude() == null || candidate.getLongitude() == null) {
                continue;
            }

            double distance = calculateHaversineDistanceMeters(
                    request.getLatitude(),
                    request.getLongitude(),
                    candidate.getLatitude(),
                    candidate.getLongitude()
            );

            if (distance <= effectiveMaxDistance) {
                long joinCount = complaintParticipantRepository.countByComplaintId(candidate.getId());
                boolean isOrig = normalizedUser != null && candidate.getCitizen() != null
                        && normalizedUser.equals(candidate.getCitizen().getMobileNumber());
                boolean hasJoined = isOrig || (normalizedUser != null &&
                        complaintParticipantRepository.existsByComplaintIdAndCitizenMobileNumber(candidate.getId(), normalizedUser));

                boolean isActive = candidate.getStatus() != null && ACTIVE_STATUSES.contains(candidate.getStatus());
                boolean eligibleForJoin = isActive && !hasJoined;

                DuplicateComplaintSummary summary = DuplicateComplaintSummary.builder()
                        .id(candidate.getId())
                        .complaintNumber(candidate.getComplaintNumber())
                        .issueType(candidate.getIssueType())
                        .status(candidate.getStatus())
                        .severity(candidate.getSeverity())
                        .priority(candidate.getPriority())
                        .latitude(candidate.getLatitude())
                        .longitude(candidate.getLongitude())
                        .imageUrl(candidate.getImageUrl())
                        .proofImageUrl(candidate.getProofImageUrl())
                        .distanceMeters(distance)
                        .joinCount(joinCount)
                        .hasJoined(hasJoined)
                        .isOriginalReporter(isOrig)
                        .eligibleForJoin(eligibleForJoin)
                        .departmentName(candidate.getDepartment() != null ? candidate.getDepartment().getName() : null)
                        .createdAt(candidate.getCreatedAt())
                        .slaDeadline(candidate.getSlaDeadline())
                        .build();

                matches.add(summary);
            }
        }

        // Sorting priority:
        // 1. Active complaints (SUBMITTED, FORWARDED, ASSIGNED, IN_PROGRESS) appear first
        // 2. Shortest distance first
        // 3. Newest created first
        matches.sort((a, b) -> {
            boolean aActive = a.getStatus() != null && ACTIVE_STATUSES.contains(a.getStatus());
            boolean bActive = b.getStatus() != null && ACTIVE_STATUSES.contains(b.getStatus());
            if (aActive != bActive) {
                return aActive ? -1 : 1;
            }
            int distComp = Double.compare(a.getDistanceMeters(), b.getDistanceMeters());
            if (distComp != 0) {
                return distComp;
            }
            if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                return b.getCreatedAt().compareTo(a.getCreatedAt());
            }
            return 0;
        });

        return CheckDuplicateResponse.builder()
                .duplicateFound(!matches.isEmpty())
                .matchCount(matches.size())
                .radiusMeters(radius)
                .matches(matches)
                .build();
    }

    /**
     * Links an authenticated citizen to an existing complaint.
     * Enforces unique participation and does not create duplicate complaints or worker assignments.
     */
    @Transactional
    public JoinComplaintResponse joinComplaint(Long complaintId, String citizenMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(citizenMobile);
        User citizen = userRepository.findByMobileNumber(normalizedMobile)
                .orElseThrow(() -> new IllegalArgumentException("Citizen user not found: " + normalizedMobile));

        if (citizen.getRole() != Role.CITIZEN) {
            throw new AccessDeniedException("Only CITIZEN can join existing complaints.");
        }

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        if (complaint.getStatus() != null && !ACTIVE_STATUSES.contains(complaint.getStatus())) {
            throw new IllegalStateException("Only active complaints (SUBMITTED, FORWARDED, ASSIGNED, IN_PROGRESS) can be joined. Current status: " + complaint.getStatus());
        }

        long currentJoinCount = complaintParticipantRepository.countByComplaintId(complaintId);

        // Check if citizen is the original reporter
        if (complaint.getCitizen() != null && complaint.getCitizen().getMobileNumber().equals(normalizedMobile)) {
            logger.info("Citizen {} is original reporter of complaint {}", normalizedMobile, complaint.getComplaintNumber());
            return JoinComplaintResponse.builder()
                    .complaintId(complaintId)
                    .complaintNumber(complaint.getComplaintNumber())
                    .message("You are the original reporter of this complaint.")
                    .joinCount(currentJoinCount)
                    .alreadyJoined(true)
                    .isOriginalReporter(true)
                    .joinedAt(complaint.getCreatedAt())
                    .build();
        }

        // Check if citizen has already joined
        Optional<ComplaintParticipant> existingOpt = complaintParticipantRepository
                .findByComplaintIdAndCitizenMobileNumber(complaintId, normalizedMobile);
        if (existingOpt.isPresent()) {
            logger.info("Citizen {} has already joined complaint {}", normalizedMobile, complaint.getComplaintNumber());
            return JoinComplaintResponse.builder()
                    .complaintId(complaintId)
                    .complaintNumber(complaint.getComplaintNumber())
                    .message("You have already joined this complaint.")
                    .joinCount(currentJoinCount)
                    .alreadyJoined(true)
                    .isOriginalReporter(false)
                    .joinedAt(existingOpt.get().getJoinedAt())
                    .build();
        }

        // Persist participant record
        ComplaintParticipant participant = ComplaintParticipant.builder()
                .complaint(complaint)
                .citizen(citizen)
                .build();

        ComplaintParticipant savedParticipant = complaintParticipantRepository.save(participant);
        long newJoinCount = complaintParticipantRepository.countByComplaintId(complaintId);

        logger.info("Citizen {} joined complaint {}. Total citizens joined: {}",
                normalizedMobile, complaint.getComplaintNumber(), newJoinCount);

        return JoinComplaintResponse.builder()
                .complaintId(complaintId)
                .complaintNumber(complaint.getComplaintNumber())
                .message("Successfully joined complaint. You will receive updates as progress is made.")
                .joinCount(newJoinCount)
                .alreadyJoined(false)
                .isOriginalReporter(false)
                .joinedAt(savedParticipant.getJoinedAt() != null ? savedParticipant.getJoinedAt() : LocalDateTime.now())
                .build();
    }

    /**
     * Checks participation status for the current user and complaint.
     */
    public Map<String, Object> getParticipationStatus(Long complaintId, String citizenMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(citizenMobile);
        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        boolean isOrig = complaint.getCitizen() != null && normalizedMobile.equals(complaint.getCitizen().getMobileNumber());
        boolean hasJoined = isOrig || complaintParticipantRepository.existsByComplaintIdAndCitizenMobileNumber(complaintId, normalizedMobile);
        long joinCount = complaintParticipantRepository.countByComplaintId(complaintId);

        return Map.of(
                "complaintId", complaintId,
                "complaintNumber", complaint.getComplaintNumber(),
                "isOriginalReporter", isOrig,
                "hasJoined", hasJoined,
                "joinCount", joinCount
        );
    }

    /**
     * Authoritative join count for a given complaint.
     */
    public Map<String, Object> getJoinCount(Long complaintId) {
        if (!complaintRepository.existsById(complaintId)) {
            throw new IllegalArgumentException("Complaint not found with ID: " + complaintId);
        }
        long joinCount = complaintParticipantRepository.countByComplaintId(complaintId);
        return Map.of(
                "complaintId", complaintId,
                "joinCount", joinCount
        );
    }

    /**
     * Retrieves complaints joined by the authenticated citizen.
     */
    public List<ComplaintResponse> getJoinedComplaints(String citizenMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(citizenMobile);
        List<Long> joinedIds = complaintParticipantRepository.findComplaintIdsByCitizenMobileNumber(normalizedMobile);
        if (joinedIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Complaint> complaints = complaintRepository.findByIdInOrderByCreatedAtDesc(joinedIds);
        return mapListToResponses(complaints, normalizedMobile);
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
        return mapToResponse(savedComplaint, normalizedMobile);
    }

    public List<ComplaintResponse> getCitizenComplaints(String citizenMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(citizenMobile);
        List<Complaint> complaints = complaintRepository.findByCitizenMobileNumberOrderByCreatedAtDesc(normalizedMobile);
        return mapListToResponses(complaints, normalizedMobile);
    }

    public ComplaintResponse getComplaintById(Long id, String currentUserMobile) {
        String normalizedMobile = PhoneNumberUtil.normalize(currentUserMobile);
        Complaint complaint = complaintRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + id));

        User currentUser = userRepository.findByMobileNumber(normalizedMobile)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + normalizedMobile));

        validateViewPermission(complaint, currentUser);

        return mapToResponse(complaint, normalizedMobile);
    }

    public List<ComplaintResponse> getAdminComplaints() {
        List<Complaint> complaints = complaintRepository.findAllByOrderByCreatedAtDesc();
        return mapListToResponses(complaints, null);
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

        // Notify original reporter and all joined citizens
        notifyAllStakeholdersOfStatusUpdate(updated, ComplaintStatus.FORWARDED, department.getName());

        logger.info("Complaint {} forwarded to department {}", complaint.getComplaintNumber(), department.getName());
        return mapToResponse(updated, normalizedAdminMobile);
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

        List<Complaint> complaints = complaintRepository.findByDepartmentIdOrderByCreatedAtDesc(supervisor.getDepartment().getId());
        return mapListToResponses(complaints, null);
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

        // Notify original reporter and all joined citizens
        notifyAllStakeholdersOfStatusUpdate(updated, ComplaintStatus.ASSIGNED, null);

        logger.info("Complaint {} assigned to worker {}", complaint.getComplaintNumber(), worker.getMobileNumber());
        return mapToResponse(updated, normalizedSupervisorMobile);
    }

    public List<ComplaintResponse> getWorkerComplaints(String workerMobile) {
        String normalizedWorkerMobile = PhoneNumberUtil.normalize(workerMobile);
        User worker = userRepository.findByMobileNumber(normalizedWorkerMobile)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + normalizedWorkerMobile));

        if (worker.getRole() != Role.WORKER) {
            throw new AccessDeniedException("User is not a WORKER.");
        }

        List<Complaint> complaints = complaintRepository.findByAssignedWorkerMobileNumberOrderByCreatedAtDesc(normalizedWorkerMobile);
        return mapListToResponses(complaints, null);
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

        // Notify original reporter and all joined citizens
        notifyAllStakeholdersOfStatusUpdate(updated, newStatus, null);

        logger.info("Complaint {} status updated from {} to {} by worker {}",
                complaint.getComplaintNumber(), oldStatus, newStatus, normalizedWorkerMobile);
        return mapToResponse(updated, normalizedWorkerMobile);
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

        return mapToResponse(updated, normalizedWorkerMobile);
    }

    public String uploadImage(MultipartFile file) {
        return fileStorageService.storeFile(file);
    }

    private void notifyAllStakeholdersOfStatusUpdate(Complaint complaint, ComplaintStatus newStatus, String departmentName) {
        Set<String> notifiedTokens = new HashSet<>();

        // 1. Notify Original Citizen
        if (complaint.getCitizen() != null && complaint.getCitizen().getFcmToken() != null) {
            String token = complaint.getCitizen().getFcmToken().trim();
            if (!token.isEmpty()) {
                notificationService.sendComplaintStatusNotification(
                        token,
                        complaint.getComplaintNumber(),
                        newStatus,
                        departmentName
                );
                notifiedTokens.add(token);
            }
        }

        // 2. Notify All Participating / Joined Citizens
        try {
            List<ComplaintParticipant> participants = complaintParticipantRepository.findByComplaintId(complaint.getId());
            for (ComplaintParticipant participant : participants) {
                if (participant.getCitizen() != null && participant.getCitizen().getFcmToken() != null) {
                    String token = participant.getCitizen().getFcmToken().trim();
                    if (!token.isEmpty() && !notifiedTokens.contains(token)) {
                        notificationService.sendComplaintStatusNotification(
                                token,
                                complaint.getComplaintNumber(),
                                newStatus,
                                departmentName
                        );
                        notifiedTokens.add(token);
                    }
                }
            }
        } catch (Exception ex) {
            logger.warn("Could not dispatch push notification to participants for complaint {}: {}",
                    complaint.getComplaintNumber(), ex.getMessage());
        }
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
            if (complaintParticipantRepository.existsByComplaintIdAndCitizenMobileNumber(complaint.getId(), user.getMobileNumber())) {
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

    private List<ComplaintResponse> mapListToResponses(List<Complaint> complaints, String currentUserMobile) {
        if (complaints == null || complaints.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> complaintIds = complaints.stream().map(Complaint::getId).collect(Collectors.toList());
        Map<Long, Long> joinCountMap = new HashMap<>();
        List<Object[]> countResults = complaintParticipantRepository.countParticipantsByComplaintIds(complaintIds);
        for (Object[] row : countResults) {
            Long cId = (Long) row[0];
            Long count = ((Number) row[1]).longValue();
            joinCountMap.put(cId, count);
        }

        Set<Long> joinedByCurrent = Collections.emptySet();
        String normalizedCurrent = currentUserMobile != null ? PhoneNumberUtil.normalize(currentUserMobile) : null;
        if (normalizedCurrent != null) {
            List<Long> userJoinedIds = complaintParticipantRepository.findComplaintIdsByCitizenMobileNumber(normalizedCurrent);
            joinedByCurrent = new HashSet<>(userJoinedIds);
        }

        final Set<Long> finalJoinedSet = joinedByCurrent;
        return complaints.stream()
                .map(c -> {
                    ComplaintResponse resp = mapToResponse(c, normalizedCurrent);
                    long jCount = joinCountMap.getOrDefault(c.getId(), 0L);
                    resp.setJoinCount(jCount);
                    if (normalizedCurrent != null) {
                        boolean isOrig = c.getCitizen() != null && normalizedCurrent.equals(c.getCitizen().getMobileNumber());
                        resp.setIsOriginalReporter(isOrig);
                        resp.setHasJoined(isOrig || finalJoinedSet.contains(c.getId()));
                    }
                    return resp;
                })
                .collect(Collectors.toList());
    }

    private ComplaintResponse mapToResponse(Complaint c) {
        return mapToResponse(c, null);
    }

    private ComplaintResponse mapToResponse(Complaint c, String currentUserMobile) {
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

        long joinCount = complaintParticipantRepository.countByComplaintId(c.getId());

        Boolean isOrig = null;
        Boolean hasJoined = null;
        if (currentUserMobile != null) {
            String norm = PhoneNumberUtil.normalize(currentUserMobile);
            isOrig = c.getCitizen() != null && norm.equals(c.getCitizen().getMobileNumber());
            hasJoined = isOrig || complaintParticipantRepository.existsByComplaintIdAndCitizenMobileNumber(c.getId(), norm);
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
                .joinCount(joinCount)
                .hasJoined(hasJoined)
                .isOriginalReporter(isOrig)
                .build();
    }

    private String generateUniqueComplaintNumber() {
        return "CMP-" + System.currentTimeMillis();
    }
}
