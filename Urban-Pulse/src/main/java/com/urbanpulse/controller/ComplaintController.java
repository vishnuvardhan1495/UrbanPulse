package com.urbanpulse.controller;

import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.CreateComplaintRequest;
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
}
