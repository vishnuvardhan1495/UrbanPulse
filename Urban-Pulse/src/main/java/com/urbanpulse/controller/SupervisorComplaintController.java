package com.urbanpulse.controller;

import com.urbanpulse.dto.AssignWorkerRequest;
import com.urbanpulse.dto.ComplaintResponse;
import com.urbanpulse.service.ComplaintService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/supervisor/complaints")
public class SupervisorComplaintController {

    private final ComplaintService complaintService;

    public SupervisorComplaintController(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<List<ComplaintResponse>> getSupervisorComplaints(Authentication authentication) {
        String supervisorMobile = authentication.getName();
        List<ComplaintResponse> complaints = complaintService.getSupervisorComplaints(supervisorMobile);
        return ResponseEntity.ok(complaints);
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<ComplaintResponse> assignWorker(@PathVariable Long id,
                                                           @Valid @RequestBody AssignWorkerRequest request,
                                                           Authentication authentication) {
        String supervisorMobile = authentication.getName();
        ComplaintResponse response = complaintService.assignWorker(id, request, supervisorMobile);
        return ResponseEntity.ok(response);
    }
}
