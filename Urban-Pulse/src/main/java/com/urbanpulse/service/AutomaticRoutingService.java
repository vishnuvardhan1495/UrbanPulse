package com.urbanpulse.service;

import com.urbanpulse.dto.AdminManualAssignRequest;
import com.urbanpulse.dto.AdminReviewApproveRequest;
import com.urbanpulse.dto.AdminReviewRejectRequest;
import com.urbanpulse.dto.MlPredictionResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service managing automated 8-class ML classification and confidence-based routing for complaints.
 *
 * Routing Rules:
 *  - Prediction Confidence >= 50% (>= 0.50):
 *      Accept predicted classification, map to responsible department, and automatically forward
 *      the complaint to the department's active supervisor. Status becomes FORWARDED.
 *
 *  - Prediction Confidence < 50% (< 0.50):
 *      Do not automatically assign to any department or supervisor. Forward complaint to Admin
 *      for manual review. Status becomes PENDING_ADMIN_REVIEW.
 *
 *  - Exact Boundary Condition:
 *      49.99% -> Admin Review (PENDING_ADMIN_REVIEW)
 *      50.00% -> Department Supervisor (FORWARDED)
 *      85.00% -> Department Supervisor (FORWARDED)
 */
@Service
public class AutomaticRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(AutomaticRoutingService.class);

    private final MlInferenceService mlInferenceService;
    private final CategoryDepartmentMappingService categoryDepartmentMappingService;
    private final ComplaintRepository complaintRepository;
    private final ComplaintStatusHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final NotificationService notificationService;
    private final SlaService slaService;

    @Value("${urbanpulse.ml.confidence-threshold:0.50}")
    private double confidenceThreshold;

    public AutomaticRoutingService(
            MlInferenceService mlInferenceService,
            CategoryDepartmentMappingService categoryDepartmentMappingService,
            ComplaintRepository complaintRepository,
            ComplaintStatusHistoryRepository historyRepository,
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            NotificationService notificationService,
            SlaService slaService) {
        this.mlInferenceService = mlInferenceService;
        this.categoryDepartmentMappingService = categoryDepartmentMappingService;
        this.complaintRepository = complaintRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.notificationService = notificationService;
        this.slaService = slaService;
    }

    /**
     * Executes single-stage 8-class ML inference on the complaint image and applies confidence-based routing.
     * Guaranteed safe: Never throws unhandled exceptions that abort the complaint submission transaction.
     */
    @Transactional
    public Complaint routeComplaint(Complaint complaint, byte[] imageBytes, String filename) {
        if (complaint == null) {
            return null;
        }

        logger.info("Executing automatic routing for complaint {} [Image: {}]",
                complaint.getComplaintNumber(), filename);

        Optional<MlPredictionResponse> predictionOpt = mlInferenceService.predictImage(imageBytes, filename);

        if (predictionOpt.isEmpty()) {
            logger.warn("ML inference service call failed for complaint {}. Routing to Admin for manual review. [Status: PENDING_ADMIN_REVIEW, Method: MANUAL_REVIEW_ML_FAILED]",
                    complaint.getComplaintNumber());
            complaint.setAssignmentMethod("MANUAL_REVIEW_ML_FAILED");
            complaint.setRequiresManualReview(true);
            complaint.setReviewStatus("PENDING_ADMIN_REVIEW");
            complaint.setClassificationStatus("FAILED");
            complaint.setClassificationSource("FALLBACK_ML_FAILED");
            complaint.setAdminReviewReason("ML service unavailable or classification failed");
            complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);
            complaint.setDepartment(null);
            complaint.setAssignedSupervisor(null);
            complaint.setAssignedWorker(null);

            Complaint saved = complaintRepository.save(complaint);
            recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.PENDING_ADMIN_REVIEW, saved.getCitizen(),
                    "ML inference unavailable. Forwarded to Admin for manual review.");
            return saved;
        }

        MlPredictionResponse prediction = predictionOpt.get();
        Double confidence = prediction.getConfidence();
        String rawCategory = prediction.getPredictedClass();
        String canonicalCategory = categoryDepartmentMappingService.normalizeCategory(rawCategory);

        complaint.setPredictedCategory(canonicalCategory);
        complaint.setConfidenceScore(confidence);
        complaint.setModelVersion(prediction.getModelVersion() != null ? prediction.getModelVersion() : "urbanpulse_8class_pothole_stage1");
        complaint.setClassificationSource("AUTO_ML");

        logger.info("8-Class ML Prediction received for complaint {}: rawCategory='{}', canonicalCategory='{}', confidence={}",
                complaint.getComplaintNumber(), rawCategory, canonicalCategory, confidence);

        // Align IssueType and SLA if classified category maps to an IssueType
        IssueType mappedIssue = categoryDepartmentMappingService.getIssueTypeForCategory(canonicalCategory);
        if (mappedIssue != null && mappedIssue != IssueType.NONE) {
            complaint.setIssueType(mappedIssue);
            int slaHours = slaService.getSlaHours(mappedIssue);
            complaint.setSlaHours(slaHours);
            complaint.setSlaDeadline(slaService.calculateDeadline(
                    complaint.getCreatedAt() != null ? complaint.getCreatedAt() : LocalDateTime.now(), mappedIssue));
        }

        // =========================================================================
        // CONFIDENCE-BASED ROUTING DECISION: Exact 50% Threshold (0.50)
        // =========================================================================
        if (confidence != null && confidence >= confidenceThreshold) {
            // ---------------------------------------------------------------------
            // CASE A: Confidence >= 50% (High Confidence) -> Department Supervisor
            // ---------------------------------------------------------------------
            Optional<String> deptNameOpt = categoryDepartmentMappingService.getDepartmentForCategory(canonicalCategory);

            if (deptNameOpt.isEmpty()) {
                logger.warn("Predicted category '{}' has no mapped department. Routing to Admin manual review. [Complaint: {}]",
                        canonicalCategory, complaint.getComplaintNumber());
                complaint.setAssignmentMethod("MANUAL_REVIEW_UNSUPPORTED_CATEGORY");
                complaint.setRequiresManualReview(true);
                complaint.setReviewStatus("PENDING_ADMIN_REVIEW");
                complaint.setClassificationStatus("PENDING_REVIEW");
                complaint.setAdminReviewReason("Predicted category '" + canonicalCategory + "' is unsupported.");
                complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);
                complaint.setDepartment(null);
                complaint.setAssignedSupervisor(null);

                Complaint saved = complaintRepository.save(complaint);
                recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.PENDING_ADMIN_REVIEW, saved.getCitizen(),
                        "ML predicted unsupported category '" + canonicalCategory + "'. Forwarded to Admin for manual review.");
                return saved;
            }

            String targetDeptName = deptNameOpt.get();
            Optional<Department> deptOpt = departmentRepository.findByName(targetDeptName);

            if (deptOpt.isEmpty()) {
                logger.warn("Mapped department '{}' does not exist in database. Routing to Admin manual review. [Complaint: {}]",
                        targetDeptName, complaint.getComplaintNumber());
                complaint.setAssignmentMethod("MANUAL_REVIEW_NO_DEPARTMENT");
                complaint.setRequiresManualReview(true);
                complaint.setReviewStatus("PENDING_ADMIN_REVIEW");
                complaint.setClassificationStatus("PENDING_REVIEW");
                complaint.setAdminReviewReason("Department '" + targetDeptName + "' does not exist in database.");
                complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);
                complaint.setDepartment(null);
                complaint.setAssignedSupervisor(null);

                Complaint saved = complaintRepository.save(complaint);
                recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.PENDING_ADMIN_REVIEW, saved.getCitizen(),
                        "Mapped department '" + targetDeptName + "' not found. Forwarded to Admin for manual review.");
                return saved;
            }

            Department department = deptOpt.get();
            List<User> supervisors = userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, department.getId());

            if (supervisors.isEmpty()) {
                logger.warn("No active supervisor found in department '{}'. Routing to Admin manual review. [Complaint: {}]",
                        department.getName(), complaint.getComplaintNumber());
                complaint.setDepartment(department);
                complaint.setAssignmentMethod("MANUAL_REVIEW_NO_SUPERVISOR");
                complaint.setRequiresManualReview(true);
                complaint.setReviewStatus("PENDING_ADMIN_REVIEW");
                complaint.setClassificationStatus("PENDING_REVIEW");
                complaint.setAdminReviewReason("No active supervisor found for " + department.getName());
                complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);

                Complaint saved = complaintRepository.save(complaint);
                recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.PENDING_ADMIN_REVIEW, saved.getCitizen(),
                        String.format("ML classified as %s (%.1f%% confidence), but no supervisor exists for %s. Forwarded to Admin for manual review.",
                                canonicalCategory, confidence * 100, department.getName()));
                return saved;
            }

            // Active supervisor found! Automatically forward complaint
            User supervisor = supervisors.get(0);
            complaint.setDepartment(department);
            complaint.setAssignedSupervisor(supervisor);
            complaint.setAssignmentMethod("AUTO_ML");
            complaint.setRequiresManualReview(false);
            complaint.setReviewStatus("AUTO_ROUTED");
            complaint.setClassificationStatus("CLASSIFIED");
            complaint.setStatus(ComplaintStatus.FORWARDED);
            complaint.setAssignedAt(LocalDateTime.now());

            Complaint saved = complaintRepository.save(complaint);

            String historyRemark = String.format("Complaint automatically forwarded to %s supervisor %s (ML confidence: %.2f%%)",
                    department.getName(),
                    supervisor.getName() != null ? supervisor.getName() : supervisor.getMobileNumber(),
                    confidence * 100);

            recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.FORWARDED, saved.getCitizen(), historyRemark);

            // Send push notification to citizen
            if (saved.getCitizen() != null && saved.getCitizen().getFcmToken() != null) {
                notificationService.sendComplaintStatusNotification(
                        saved.getCitizen().getFcmToken(),
                        saved.getComplaintNumber(),
                        ComplaintStatus.FORWARDED,
                        department.getName()
                );
            }

            // Send push notification to assigned supervisor
            if (supervisor.getFcmToken() != null && !supervisor.getFcmToken().trim().isEmpty()) {
                notificationService.sendComplaintStatusNotification(
                        supervisor.getFcmToken(),
                        saved.getComplaintNumber(),
                        ComplaintStatus.FORWARDED,
                        department.getName()
                );
            }

            logger.info("Routing decision: AUTO_ML. Complaint {} automatically forwarded to {} supervisor {} [Category: {}, Confidence: {}%, Status: FORWARDED]",
                    saved.getComplaintNumber(), department.getName(), supervisor.getMobileNumber(), canonicalCategory, String.format("%.2f", confidence * 100));
            return saved;

        } else {
            // ---------------------------------------------------------------------
            // CASE B: Confidence < 50% (Low Confidence) -> Admin Review
            // ---------------------------------------------------------------------
            String confStr = confidence != null ? String.format("%.2f%%", confidence * 100) : "N/A";
            logger.info("Routing decision: MANUAL_REVIEW_LOW_CONFIDENCE. Complaint {} has low ML confidence ({} < 50%). Routed to Admin [Category: {}, Status: PENDING_ADMIN_REVIEW]",
                    complaint.getComplaintNumber(), confStr, canonicalCategory);

            complaint.setDepartment(null);
            complaint.setAssignedSupervisor(null);
            complaint.setAssignedWorker(null);
            complaint.setAssignmentMethod("MANUAL_REVIEW_LOW_CONFIDENCE");
            complaint.setRequiresManualReview(true);
            complaint.setReviewStatus("PENDING_ADMIN_REVIEW");
            complaint.setClassificationStatus("PENDING_REVIEW");
            complaint.setAdminReviewReason("Low model confidence: " + confStr + " (< 50%). Forwarded to Admin for manual review.");
            complaint.setStatus(ComplaintStatus.PENDING_ADMIN_REVIEW);

            Complaint saved = complaintRepository.save(complaint);
            recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.PENDING_ADMIN_REVIEW, saved.getCitizen(),
                    "Low model confidence (" + confStr + " < 50%). Forwarded to Admin for manual review.");
            return saved;
        }
    }

    /**
     * Admin Review Action: Approve Complaint.
     * Admin manually verifies complaint, selects/confirms 1 of the 8 categories,
     * routes it to the department supervisor, and updates review audit trail.
     */
    @Transactional
    public Complaint approveLowConfidenceComplaintByAdmin(Long complaintId, AdminReviewApproveRequest request, String adminMobile) {
        String normalizedAdminMobile = PhoneNumberUtil.normalize(adminMobile);
        User admin = userRepository.findByMobileNumber(normalizedAdminMobile)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + normalizedAdminMobile));

        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only ADMIN can review and approve complaints.");
        }

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        if (complaint.getStatus() == ComplaintStatus.FORWARDED ||
            complaint.getStatus() == ComplaintStatus.ASSIGNED ||
            complaint.getStatus() == ComplaintStatus.IN_PROGRESS ||
            complaint.getStatus() == ComplaintStatus.RESOLVED) {
            throw new IllegalStateException("Complaint " + complaint.getComplaintNumber() + " is already approved and routed.");
        }

        if (request.getCategory() == null || request.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("Category selection is required for manual approval.");
        }

        String rawCategory = request.getCategory().trim();
        String canonicalCategory = categoryDepartmentMappingService.normalizeCategory(rawCategory);

        Optional<String> deptNameOpt = categoryDepartmentMappingService.getDepartmentForCategory(canonicalCategory);
        if (deptNameOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid civic category: '" + rawCategory + "'. Must be one of the eight canonical categories.");
        }

        Department department;
        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + request.getDepartmentId()));
        } else {
            String targetDeptName = deptNameOpt.get();
            department = departmentRepository.findByName(targetDeptName)
                    .orElseThrow(() -> new IllegalArgumentException("Department '" + targetDeptName + "' not found in database."));
        }

        User supervisor = null;
        if (request.getSupervisorMobile() != null && !request.getSupervisorMobile().trim().isEmpty()) {
            String normSupMobile = PhoneNumberUtil.normalize(request.getSupervisorMobile());
            supervisor = userRepository.findByMobileNumber(normSupMobile)
                    .orElseThrow(() -> new IllegalArgumentException("Supervisor not found: " + normSupMobile));
            if (supervisor.getRole() != Role.SUPERVISOR) {
                throw new IllegalArgumentException("Selected user is not a SUPERVISOR: " + normSupMobile);
            }
        } else {
            List<User> supervisors = userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, department.getId());
            if (!supervisors.isEmpty()) {
                supervisor = supervisors.get(0);
            }
        }

        complaint.setPredictedCategory(canonicalCategory);
        IssueType issue = categoryDepartmentMappingService.getIssueTypeForCategory(canonicalCategory);
        if (issue != null && issue != IssueType.NONE) {
            complaint.setIssueType(issue);
            int slaHours = slaService.getSlaHours(issue);
            complaint.setSlaHours(slaHours);
            complaint.setSlaDeadline(slaService.calculateDeadline(
                    complaint.getCreatedAt() != null ? complaint.getCreatedAt() : LocalDateTime.now(), issue));
        }

        ComplaintStatus oldStatus = complaint.getStatus();
        complaint.setClassificationSource("ADMIN_VERIFIED");
        complaint.setClassificationStatus("CLASSIFIED");
        complaint.setReviewStatus("ADMIN_APPROVED");
        complaint.setAdminReviewedBy(admin.getMobileNumber());
        complaint.setAdminReviewedAt(LocalDateTime.now());
        complaint.setAdminReviewRemarks(request.getRemarks() != null ? request.getRemarks().trim() : null);
        complaint.setDepartment(department);
        complaint.setAssignedSupervisor(supervisor);
        complaint.setStatus(ComplaintStatus.FORWARDED);
        complaint.setAssignmentMethod("ADMIN_MANUAL_APPROVAL");
        complaint.setRequiresManualReview(false);
        complaint.setAdminReviewReason(null);
        complaint.setAssignedAt(LocalDateTime.now());

        Complaint saved = complaintRepository.save(complaint);

        String remark = "Admin manual review: Approved complaint under category '" + canonicalCategory +
                "', routed to " + department.getName() +
                (supervisor != null ? " (Supervisor: " + (supervisor.getName() != null ? supervisor.getName() : supervisor.getMobileNumber()) + ")" : "") +
                (request.getRemarks() != null && !request.getRemarks().trim().isEmpty() ? ". Remarks: " + request.getRemarks().trim() : "");

        recordStatusHistory(saved, oldStatus, ComplaintStatus.FORWARDED, admin, remark);

        // Notify citizen
        if (saved.getCitizen() != null && saved.getCitizen().getFcmToken() != null) {
            notificationService.sendComplaintStatusNotification(
                    saved.getCitizen().getFcmToken(),
                    saved.getComplaintNumber(),
                    ComplaintStatus.FORWARDED,
                    department.getName()
            );
        }

        // Notify supervisor
        if (supervisor != null && supervisor.getFcmToken() != null && !supervisor.getFcmToken().trim().isEmpty()) {
            notificationService.sendComplaintStatusNotification(
                    supervisor.getFcmToken(),
                    saved.getComplaintNumber(),
                    ComplaintStatus.FORWARDED,
                    department.getName()
            );
        }

        logger.info("Admin {} approved complaint {} as {} and routed to {}",
                normalizedAdminMobile, saved.getComplaintNumber(), canonicalCategory, department.getName());
        return saved;
    }

    /**
     * Backward-compatible alias for approveLowConfidenceComplaintByAdmin.
     */
    @Transactional
    public Complaint approveNonCivicComplaintByAdmin(Long complaintId, AdminReviewApproveRequest request, String adminMobile) {
        return approveLowConfidenceComplaintByAdmin(complaintId, request, adminMobile);
    }

    /**
     * Admin Review Action: Reject Complaint.
     * Admin marks complaint as rejected with mandatory rejection reason.
     */
    @Transactional
    public Complaint rejectLowConfidenceComplaintByAdmin(Long complaintId, AdminReviewRejectRequest request, String adminMobile) {
        String normalizedAdminMobile = PhoneNumberUtil.normalize(adminMobile);
        User admin = userRepository.findByMobileNumber(normalizedAdminMobile)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + normalizedAdminMobile));

        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only ADMIN can review and reject complaints.");
        }

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        if (request.getRejectionReason() == null || request.getRejectionReason().trim().isEmpty()) {
            throw new IllegalArgumentException("Rejection reason is required.");
        }

        ComplaintStatus oldStatus = complaint.getStatus();
        complaint.setStatus(ComplaintStatus.REJECTED);
        complaint.setReviewStatus("ADMIN_REJECTED");
        complaint.setClassificationStatus("REJECTED");
        complaint.setClassificationSource("ADMIN_VERIFIED");
        complaint.setAdminReviewedBy(admin.getMobileNumber());
        complaint.setAdminReviewedAt(LocalDateTime.now());
        complaint.setAdminRejectionReason(request.getRejectionReason().trim());
        complaint.setAdminReviewRemarks(request.getRemarks() != null ? request.getRemarks().trim() : null);
        complaint.setRequiresManualReview(false);
        complaint.setDepartment(null);
        complaint.setAssignedSupervisor(null);
        complaint.setAssignedWorker(null);

        Complaint saved = complaintRepository.save(complaint);

        String remark = "Admin manual review: Rejected complaint. Reason: " + request.getRejectionReason().trim() +
                (request.getRemarks() != null && !request.getRemarks().trim().isEmpty() ? ". Remarks: " + request.getRemarks().trim() : "");

        recordStatusHistory(saved, oldStatus, ComplaintStatus.REJECTED, admin, remark);

        // Notify citizen
        if (saved.getCitizen() != null && saved.getCitizen().getFcmToken() != null) {
            notificationService.sendComplaintStatusNotification(
                    saved.getCitizen().getFcmToken(),
                    saved.getComplaintNumber(),
                    ComplaintStatus.REJECTED,
                    "Admin Review"
            );
        }

        logger.info("Admin {} rejected complaint {}. Reason: {}",
                normalizedAdminMobile, saved.getComplaintNumber(), request.getRejectionReason().trim());
        return saved;
    }

    /**
     * Backward-compatible alias for rejectLowConfidenceComplaintByAdmin.
     */
    @Transactional
    public Complaint rejectNonCivicComplaintByAdmin(Long complaintId, AdminReviewRejectRequest request, String adminMobile) {
        return rejectLowConfidenceComplaintByAdmin(complaintId, request, adminMobile);
    }

    /**
     * Admin manual assignment action (overrides/sets department, supervisor, category, and clears manual review flag).
     */
    @Transactional
    public Complaint manualAssignByAdmin(Long complaintId, AdminManualAssignRequest request, String adminMobile) {
        String normalizedAdminMobile = PhoneNumberUtil.normalize(adminMobile);
        User admin = userRepository.findByMobileNumber(normalizedAdminMobile)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + normalizedAdminMobile));

        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only ADMIN can manually review and assign complaints.");
        }

        Complaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found with ID: " + complaintId));

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + request.getDepartmentId()));

        User supervisor = null;
        if (request.getSupervisorMobile() != null && !request.getSupervisorMobile().trim().isEmpty()) {
            String normSupMobile = PhoneNumberUtil.normalize(request.getSupervisorMobile());
            supervisor = userRepository.findByMobileNumber(normSupMobile)
                    .orElseThrow(() -> new IllegalArgumentException("Supervisor not found: " + normSupMobile));
            if (supervisor.getRole() != Role.SUPERVISOR) {
                throw new IllegalArgumentException("Selected user is not a SUPERVISOR: " + normSupMobile);
            }
        } else {
            List<User> supervisors = userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, department.getId());
            if (!supervisors.isEmpty()) {
                supervisor = supervisors.get(0);
            }
        }

        if (request.getCategory() != null && !request.getCategory().trim().isEmpty()) {
            String normCat = categoryDepartmentMappingService.normalizeCategory(request.getCategory());
            complaint.setPredictedCategory(normCat);
            IssueType issue = categoryDepartmentMappingService.getIssueTypeForCategory(normCat);
            if (issue != null && issue != IssueType.NONE) {
                complaint.setIssueType(issue);
            }
        }

        ComplaintStatus oldStatus = complaint.getStatus();
        complaint.setDepartment(department);
        complaint.setAssignedSupervisor(supervisor);
        complaint.setAssignedWorker(null);
        complaint.setStatus(ComplaintStatus.FORWARDED);
        complaint.setAssignmentMethod("MANUAL_ADMIN");
        complaint.setReviewStatus("ADMIN_APPROVED");
        complaint.setClassificationSource("ADMIN_VERIFIED");
        complaint.setClassificationStatus("CLASSIFIED");
        complaint.setAdminReviewedBy(admin.getMobileNumber());
        complaint.setAdminReviewedAt(LocalDateTime.now());
        complaint.setRequiresManualReview(false);
        complaint.setAdminReviewReason(null);
        complaint.setAssignedAt(LocalDateTime.now());

        Complaint saved = complaintRepository.save(complaint);

        String remark = "Manually reviewed and forwarded by Admin to " + department.getName() +
                (supervisor != null ? " (Supervisor: " + (supervisor.getName() != null ? supervisor.getName() : supervisor.getMobileNumber()) + ")" : "") +
                (request.getRemarks() != null && !request.getRemarks().trim().isEmpty() ? " Remarks: " + request.getRemarks().trim() : "");

        recordStatusHistory(saved, oldStatus, ComplaintStatus.FORWARDED, admin, remark);

        // Notify citizen
        if (saved.getCitizen() != null && saved.getCitizen().getFcmToken() != null) {
            notificationService.sendComplaintStatusNotification(
                    saved.getCitizen().getFcmToken(),
                    saved.getComplaintNumber(),
                    ComplaintStatus.FORWARDED,
                    department.getName()
            );
        }

        // Notify supervisor if assigned
        if (supervisor != null && supervisor.getFcmToken() != null && !supervisor.getFcmToken().trim().isEmpty()) {
            notificationService.sendComplaintStatusNotification(
                    supervisor.getFcmToken(),
                    saved.getComplaintNumber(),
                    ComplaintStatus.FORWARDED,
                    department.getName()
            );
        }

        logger.info("Admin {} manually assigned complaint {} to department {}",
                normalizedAdminMobile, saved.getComplaintNumber(), department.getName());
        return saved;
    }

    private void recordStatusHistory(Complaint complaint, ComplaintStatus oldStatus, ComplaintStatus newStatus, User changedBy, String remarks) {
        User effectiveActor = changedBy != null ? changedBy : (complaint != null ? complaint.getCitizen() : null);
        ComplaintStatusHistory history = ComplaintStatusHistory.builder()
                .complaint(complaint)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .changedBy(effectiveActor)
                .remarks(remarks)
                .build();
        historyRepository.save(history);
    }
}
