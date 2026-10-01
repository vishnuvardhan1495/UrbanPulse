package com.urbanpulse.controller;

import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.UserRepository;
import com.urbanpulse.service.FirebaseAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ComplaintControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FirebaseAuthService firebaseAuthService;

    @MockBean
    private UserRepository userRepository;

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
}
