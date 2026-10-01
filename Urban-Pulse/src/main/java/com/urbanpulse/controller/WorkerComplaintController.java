package com.urbanpulse.controller;

import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.dto.UpdateStatusRequest;
import com.urbanpulse.service.ComplaintService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/worker/complaints")
public class WorkerComplaintController {

    private final ComplaintService complaintService;

    public WorkerComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @GetMapping
    @PreAuthorize("hasRole('WORKER')")
    public ResponseEntity<List<ComplaintResponse>> getWorkerComplaints(Authentication authentication) {
        String workerMobile = authentication.getName();
        List<ComplaintResponse> complaints = complaintService.getWorkerComplaints(workerMobile);
        return ResponseEntity.ok(complaints);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('WORKER')")
    public ResponseEntity<ComplaintResponse> updateWorkerStatus(@PathVariable Long id,
                                                                 @Valid @RequestBody UpdateStatusRequest request,
                                                                 Authentication authentication) {
        String workerMobile = authentication.getName();
        ComplaintResponse response = complaintService.updateWorkerStatus(id, request, workerMobile);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/proof")
    @PreAuthorize("hasRole('WORKER')")
    public ResponseEntity<ComplaintResponse> uploadProof(@PathVariable Long id,
                                                         @RequestParam("file") MultipartFile file,
                                                         Authentication authentication) {
        String workerMobile = authentication.getName();
        ComplaintResponse response = complaintService.uploadProofImage(id, file, workerMobile);
        return ResponseEntity.ok(response);
    }
}
