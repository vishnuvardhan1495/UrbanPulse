package com.urbanpulse.controller;

import com.urbanpulse.dto.CheckDuplicateRequest;
import com.urbanpulse.dto.CheckDuplicateResponse;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
import com.urbanpulse.dto.JoinComplaintResponse;
import com.urbanpulse.service.ComplaintService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {

    private final ComplaintService complaintService;

    public ComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @PostMapping
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<ComplaintResponse> createComplaint(@Valid @RequestBody CreateComplaintRequest request,
                                                             Authentication authentication) {
        String citizenMobile = authentication.getName();
        ComplaintResponse response = complaintService.createComplaint(request, citizenMobile);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<List<ComplaintResponse>> getMyComplaints(Authentication authentication) {
        String citizenMobile = authentication.getName();
        List<ComplaintResponse> complaints = complaintService.getCitizenComplaints(citizenMobile);
        return ResponseEntity.ok(complaints);
    }

    @GetMapping("/joined")
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<List<ComplaintResponse>> getJoinedComplaints(Authentication authentication) {
        String citizenMobile = authentication.getName();
        List<ComplaintResponse> complaints = complaintService.getJoinedComplaints(citizenMobile);
        return ResponseEntity.ok(complaints);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ComplaintResponse> getComplaintById(@PathVariable Long id,
                                                             Authentication authentication) {
        String currentUserMobile = authentication.getName();
        ComplaintResponse complaint = complaintService.getComplaintById(id, currentUserMobile);
        return ResponseEntity.ok(complaint);
    }

    @PostMapping("/upload-image")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        String imageUrl = complaintService.uploadImage(file);
        return ResponseEntity.ok(Map.of("imageUrl", imageUrl));
    }

    @PostMapping("/check-duplicates")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CheckDuplicateResponse> checkDuplicates(@Valid @RequestBody CheckDuplicateRequest request,
                                                                 Authentication authentication) {
        String currentUserMobile = authentication != null ? authentication.getName() : null;
        CheckDuplicateResponse response = complaintService.checkDuplicates(request, currentUserMobile);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/join")
    @PreAuthorize("hasRole('CITIZEN')")
    public ResponseEntity<JoinComplaintResponse> joinComplaint(@PathVariable Long id,
                                                              Authentication authentication) {
        String citizenMobile = authentication.getName();
        JoinComplaintResponse response = complaintService.joinComplaint(id, citizenMobile);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/participation")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getParticipation(@PathVariable Long id,
                                                                Authentication authentication) {
        String currentUserMobile = authentication.getName();
        Map<String, Object> participation = complaintService.getParticipationStatus(id, currentUserMobile);
        return ResponseEntity.ok(participation);
    }

    @GetMapping("/{id}/join-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getJoinCount(@PathVariable Long id) {
        Map<String, Object> count = complaintService.getJoinCount(id);
        return ResponseEntity.ok(count);
    }
}
