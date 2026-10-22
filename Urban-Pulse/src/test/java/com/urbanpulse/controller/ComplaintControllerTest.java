package com.urbanpulse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanpulse.dto.CheckDuplicateRequest;
import com.urbanpulse.dto.CheckDuplicateResponse;
import com.urbanpulse.dto.JoinComplaintResponse;
import com.urbanpulse.entity.IssueType;
import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.UserRepository;
import com.urbanpulse.service.ComplaintService;
import com.urbanpulse.service.FirebaseAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ComplaintControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FirebaseAuthService firebaseAuthService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ComplaintService complaintService;

    private User citizenUser;
    private User workerUser;
    private User adminUser;

    @BeforeEach
    void setUp() throws Exception {
        citizenUser = User.builder().mobileNumber("+91 9000000001").name("Citizen Test").role(Role.CITIZEN).build();
        workerUser = User.builder().mobileNumber("+91 9000000002").name("Worker Test").role(Role.WORKER).build();
        adminUser = User.builder().mobileNumber("+91 9000000003").name("Admin Test").role(Role.ADMIN).build();

        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(firebaseAuthService.verifyIdToken(anyString())).thenReturn(token);
    }

    @Test
    @DisplayName("Unauthenticated request to /api/complaints/my returns 401 Unauthorized")
    void testUnauthenticatedAccess_Returns401() throws Exception {
        mockMvc.perform(get("/api/complaints/my"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Citizen requesting Admin endpoint /api/admin/complaints returns 403 Forbidden")
    void testCitizenAccessAdminEndpoint_Returns403() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getClaims()).thenReturn(java.util.Map.of("phone_number", "+919000000001"));
        when(firebaseAuthService.verifyIdToken("citizen-token")).thenReturn(token);
        when(userRepository.findByMobileNumber("+91 9000000001")).thenReturn(Optional.of(citizenUser));

        mockMvc.perform(get("/api/admin/complaints")
                        .header("Authorization", "Bearer citizen-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Worker requesting Admin endpoint /api/admin/complaints returns 403 Forbidden")
    void testWorkerAccessAdminEndpoint_Returns403() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getClaims()).thenReturn(java.util.Map.of("phone_number", "+919000000002"));
        when(firebaseAuthService.verifyIdToken("worker-token")).thenReturn(token);
        when(userRepository.findByMobileNumber("+91 9000000002")).thenReturn(Optional.of(workerUser));

        mockMvc.perform(get("/api/admin/complaints")
                        .header("Authorization", "Bearer worker-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin accessing /api/admin/complaints returns 200 OK")
    void testAdminAccessAdminEndpoint_Returns200() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getClaims()).thenReturn(java.util.Map.of("phone_number", "+919000000003"));
        when(firebaseAuthService.verifyIdToken("admin-token")).thenReturn(token);
        when(userRepository.findByMobileNumber("+91 9000000003")).thenReturn(Optional.of(adminUser));

        mockMvc.perform(get("/api/admin/complaints")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated check-duplicates returns 401")
    void testCheckDuplicates_Unauthenticated_Returns401() throws Exception {
        CheckDuplicateRequest request = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.5062)
                .longitude(80.6480)
                .build();

        mockMvc.perform(post("/api/complaints/check-duplicates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated citizen check-duplicates returns 200 OK")
    void testCheckDuplicates_Authenticated_Returns200() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getClaims()).thenReturn(java.util.Map.of("phone_number", "+919000000001"));
        when(firebaseAuthService.verifyIdToken("citizen-token")).thenReturn(token);
        when(userRepository.findByMobileNumber("+91 9000000001")).thenReturn(Optional.of(citizenUser));

        CheckDuplicateResponse mockResponse = CheckDuplicateResponse.builder()
                .duplicateFound(false)
                .matchCount(0)
                .matches(Collections.emptyList())
                .build();
        when(complaintService.checkDuplicates(any(CheckDuplicateRequest.class), anyString())).thenReturn(mockResponse);

        CheckDuplicateRequest request = CheckDuplicateRequest.builder()
                .issueType(IssueType.POTHOLE)
                .latitude(16.5062)
                .longitude(80.6480)
                .build();

        mockMvc.perform(post("/api/complaints/check-duplicates")
                        .header("Authorization", "Bearer citizen-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateFound").value(false));
    }

    @Test
    @DisplayName("Citizen joins complaint endpoint /api/complaints/{id}/join returns 200 OK")
    void testJoinComplaint_CitizenRole_Returns200() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getClaims()).thenReturn(java.util.Map.of("phone_number", "+919000000001"));
        when(firebaseAuthService.verifyIdToken("citizen-token")).thenReturn(token);
        when(userRepository.findByMobileNumber("+91 9000000001")).thenReturn(Optional.of(citizenUser));

        JoinComplaintResponse joinResponse = JoinComplaintResponse.builder()
                .complaintId(10L)
                .complaintNumber("CMP-10")
                .message("Successfully joined complaint.")
                .joinCount(2L)
                .alreadyJoined(false)
                .build();
        when(complaintService.joinComplaint(eq(10L), anyString())).thenReturn(joinResponse);

        mockMvc.perform(post("/api/complaints/10/join")
                        .header("Authorization", "Bearer citizen-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complaintId").value(10))
                .andExpect(jsonPath("$.joinCount").value(2));
    }

    @Test
    @DisplayName("Worker attempting to join complaint returns 403 Forbidden")
    void testJoinComplaint_WorkerRole_Returns403() throws Exception {
        com.google.firebase.auth.FirebaseToken token = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        when(token.getClaims()).thenReturn(java.util.Map.of("phone_number", "+919000000002"));
        when(firebaseAuthService.verifyIdToken("worker-token")).thenReturn(token);
        when(userRepository.findByMobileNumber("+91 9000000002")).thenReturn(Optional.of(workerUser));

        mockMvc.perform(post("/api/complaints/10/join")
                        .header("Authorization", "Bearer worker-token"))
                .andExpect(status().isForbidden());
    }
}
