package com.urbanpulse.service;

import com.urbanpulse.dto.AdminManualAssignRequest;
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
 * Service managing automated ML classification and confidence-based routing for complaints.
 *
 * Exact 50% confidence rule:
 *  - Confidence > 50%: Automatically forward to the mapped department's active supervisor.
 *  - Confidence <= 50%: Send to Admin for manual review (requiresManualReview = true).
 *
 * Fallbacks:
 *  - ML service failure -> Admin manual review.
 *  - Unsupported category -> Admin manual review.
 *  - No active supervisor -> Admin manual review with mapped department retained.
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
     * Executes ML inference on the complaint image and applies confidence-based routing.
     * Guaranteed safe: Never throws exceptions that abort complaint submission.
     */
    @Transactional
    public Complaint routeComplaint(Complaint complaint, byte[] imageBytes, String filename) {
        if (complaint == null) {
            return null;
        }

        logger.info("Executing automatic routing for complaint {}", complaint.getComplaintNumber());

        Optional<MlPredictionResponse> predictionOpt = mlInferenceService.predictImage(imageBytes, filename);

        if (predictionOpt.isEmpty()) {
            logger.warn("ML inference service call failed for complaint {}. Routing to Admin for manual review.",
                    complaint.getComplaintNumber());
            complaint.setAssignmentMethod("MANUAL_REVIEW_ML_FAILED");
            complaint.setRequiresManualReview(true);
            complaint.setAdminReviewReason("ML service unavailable or classification failed");
            complaint.setStatus(ComplaintStatus.SUBMITTED);

            Complaint saved = complaintRepository.save(complaint);
            recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.SUBMITTED, saved.getCitizen(),
                    "ML inference unavailable. Routed to Admin for manual review.");
            return saved;
        }

        MlPredictionResponse prediction = predictionOpt.get();
        Double confidence = prediction.getConfidence();
        String rawCategory = prediction.getPredictedClass();
        String canonicalCategory = categoryDepartmentMappingService.normalizeCategory(rawCategory);

        complaint.setPredictedCategory(canonicalCategory);
        complaint.setConfidenceScore(confidence);
        complaint.setModelVersion(prediction.getModelVersion() != null ? prediction.getModelVersion() : "urbanpulse_8class_pothole_stage1");

        // Align IssueType and SLA if classified category maps to an IssueType
        IssueType mappedIssue = categoryDepartmentMappingService.getIssueTypeForCategory(canonicalCategory);
        if (mappedIssue != null && mappedIssue != IssueType.NONE) {
            complaint.setIssueType(mappedIssue);
            int slaHours = slaService.getSlaHours(mappedIssue);
            complaint.setSlaHours(slaHours);
            complaint.setSlaDeadline(slaService.calculateDeadline(complaint.getCreatedAt() != null ? complaint.getCreatedAt() : LocalDateTime.now(), mappedIssue));
        }

        // Apply exact 50% confidence rule (> 0.50 vs <= 0.50)
        if (confidence != null && confidence > confidenceThreshold) {
            Optional<String> deptNameOpt = categoryDepartmentMappingService.getDepartmentForCategory(canonicalCategory);

            if (deptNameOpt.isEmpty()) {
                logger.warn("Predicted category '{}' has no mapped department. Routing complaint {} to Admin.",
                        canonicalCategory, complaint.getComplaintNumber());
                complaint.setAssignmentMethod("MANUAL_REVIEW_UNSUPPORTED_CATEGORY");
                complaint.setRequiresManualReview(true);
                complaint.setAdminReviewReason("Predicted category '" + canonicalCategory + "' is unsupported.");
                complaint.setStatus(ComplaintStatus.SUBMITTED);

                Complaint saved = complaintRepository.save(complaint);
                recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.SUBMITTED, saved.getCitizen(),
                        "ML predicted unsupported category '" + canonicalCategory + "'. Routed to Admin for manual review.");
                return saved;
            }

            String targetDeptName = deptNameOpt.get();
            Optional<Department> deptOpt = departmentRepository.findByName(targetDeptName);

            if (deptOpt.isEmpty()) {
                logger.warn("Mapped department '{}' does not exist in database. Routing complaint {} to Admin.",
                        targetDeptName, complaint.getComplaintNumber());
                complaint.setAssignmentMethod("MANUAL_REVIEW_NO_DEPARTMENT");
                complaint.setRequiresManualReview(true);
                complaint.setAdminReviewReason("Department '" + targetDeptName + "' does not exist in database.");
                complaint.setStatus(ComplaintStatus.SUBMITTED);

                Complaint saved = complaintRepository.save(complaint);
                recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.SUBMITTED, saved.getCitizen(),
                        "Mapped department '" + targetDeptName + "' not found. Routed to Admin for manual review.");
                return saved;
            }

            Department department = deptOpt.get();
            List<User> supervisors = userRepository.findByRoleAndDepartmentId(Role.SUPERVISOR, department.getId());

            if (supervisors.isEmpty()) {
                logger.warn("No active supervisor found in department '{}'. Routing complaint {} to Admin.",
                        department.getName(), complaint.getComplaintNumber());
                complaint.setDepartment(department);
                complaint.setAssignmentMethod("ML_UNASSIGNED_NO_SUPERVISOR");
                complaint.setRequiresManualReview(true);
                complaint.setAdminReviewReason("No active supervisor found for " + department.getName());
                complaint.setStatus(ComplaintStatus.SUBMITTED);

                Complaint saved = complaintRepository.save(complaint);
                recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.SUBMITTED, saved.getCitizen(),
                        String.format("ML classified as %s (%.1f%% confidence), but no supervisor exists for %s. Routed to Admin for manual review.",
                                canonicalCategory, confidence * 100, department.getName()));
                return saved;
            }

            // Active supervisor found! Automatically forward complaint
            User supervisor = supervisors.get(0);
            complaint.setDepartment(department);
            complaint.setAssignedSupervisor(supervisor);
            complaint.setAssignmentMethod("AUTOMATIC_ML");
            complaint.setRequiresManualReview(false);
            complaint.setStatus(ComplaintStatus.FORWARDED);
            complaint.setAssignedAt(LocalDateTime.now());

            Complaint saved = complaintRepository.save(complaint);

            String historyRemark = String.format("Complaint automatically forwarded to %s supervisor %s (ML confidence: %.1f%%)",
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

            logger.info("Complaint {} automatically forwarded to {} supervisor {}",
                    saved.getComplaintNumber(), department.getName(), supervisor.getMobileNumber());
            return saved;

        } else {
            // Confidence <= 50%: Route to Admin for manual review
            String confStr = confidence != null ? String.format("%.1f%%", confidence * 100) : "N/A";
            logger.info("Complaint {} has low ML confidence ({} <= 50%). Routing to Admin for manual review.",
                    complaint.getComplaintNumber(), confStr);

            complaint.setAssignmentMethod("MANUAL_REVIEW_LOW_CONFIDENCE");
            complaint.setRequiresManualReview(true);
            complaint.setAdminReviewReason("Low model confidence: " + confStr + " <= 50%");
            complaint.setStatus(ComplaintStatus.SUBMITTED);

            Complaint saved = complaintRepository.save(complaint);
            recordStatusHistory(saved, ComplaintStatus.SUBMITTED, ComplaintStatus.SUBMITTED, saved.getCitizen(),
                    "Low model confidence (" + confStr + " <= 50%). Routed to Admin for manual review.");
            return saved;
        }
    }

    /**
     * Admin manual assignment action:
     * Overrides/sets department, supervisor, category, and clears manual review flag.
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
        complaint.setStatus(ComplaintStatus.FORWARDED);
        complaint.setAssignmentMethod("MANUAL_ADMIN");
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
