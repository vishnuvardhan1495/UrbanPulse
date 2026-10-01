package com.urbanpulse.security;

import com.google.firebase.auth.FirebaseToken;
import com.urbanpulse.entity.Department;
import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.DepartmentRepository;
import com.urbanpulse.repository.UserRepository;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FirebaseAuthService firebaseAuthService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private DepartmentRepository departmentRepository;

    private Department mockDepartment;
    private User mockCitizen;
    private User mockWorker;
    private User mockSupervisor;
    private User mockAdmin;

    @BeforeEach
    void setUp() {
        mockDepartment = Department.builder()
                .id(1L)
                .name("ROADS")
                .description("Roads Department")
                .build();

        mockCitizen = User.builder()
                .mobileNumber("+91 9000000001")
                .name("Test Citizen")
                .role(Role.CITIZEN)
                .build();

        mockWorker = User.builder()
                .mobileNumber("+91 9000000002")
                .name("Test Worker")
                .role(Role.WORKER)
                .department(mockDepartment)
                .build();

        mockSupervisor = User.builder()
                .mobileNumber("+91 9000000003")
                .name("Test Supervisor")
                .role(Role.SUPERVISOR)
                .department(mockDepartment)
                .build();

        mockAdmin = User.builder()
                .mobileNumber("+91 9000000004")
                .name("Test Admin")
                .role(Role.ADMIN)
                .build();
    }

    private FirebaseToken createMockFirebaseToken(String uid, String phoneNumber) throws Exception {
        FirebaseToken mockToken = org.mockito.Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn(uid);
        when(mockToken.getClaims()).thenReturn(Map.of("phone_number", phoneNumber));
        return mockToken;
    }

    @Test
    @DisplayName("Public security-test endpoint allows unauthenticated request")
    void testPublicEndpointUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/security-test/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Public endpoint accessible without authentication"));
    }

    @Test
    @DisplayName("Protected endpoint without Authorization header returns HTTP 401 Unauthorized")
    void testProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/security-test/citizen"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("CITIZEN cannot access admin endpoint (403 Forbidden)")
    void testCitizenCannotAccessAdmin() throws Exception {
        FirebaseToken mockToken = createMockFirebaseToken("uid-citizen", "+919000000001");
        when(firebaseAuthService.verifyIdToken("citizen-token")).thenReturn(mockToken);
        when(userRepository.findByMobileNumber("+91 9000000001")).thenReturn(Optional.of(mockCitizen));

        mockMvc.perform(get("/api/security-test/admin")
                        .header("Authorization", "Bearer citizen-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CITIZEN cannot create staff accounts (403 Forbidden)")
    void testCitizenCannotCreateStaff() throws Exception {
        FirebaseToken mockToken = createMockFirebaseToken("uid-citizen", "+919000000001");
        when(firebaseAuthService.verifyIdToken("citizen-token")).thenReturn(mockToken);
        when(userRepository.findByMobileNumber("+91 9000000001")).thenReturn(Optional.of(mockCitizen));

        mockMvc.perform(post("/api/users/staff")
                        .header("Authorization", "Bearer citizen-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobileNumber\":\"9876543210\",\"name\":\"New Staff\",\"role\":\"WORKER\",\"departmentId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("WORKER cannot create staff accounts (403 Forbidden)")
    void testWorkerCannotCreateStaff() throws Exception {
        FirebaseToken mockToken = createMockFirebaseToken("uid-worker", "+919000000002");
        when(firebaseAuthService.verifyIdToken("worker-token")).thenReturn(mockToken);
        when(userRepository.findByMobileNumber("+91 9000000002")).thenReturn(Optional.of(mockWorker));

        mockMvc.perform(post("/api/users/staff")
                        .header("Authorization", "Bearer worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobileNumber\":\"9876543210\",\"name\":\"New Staff\",\"role\":\"WORKER\",\"departmentId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SUPERVISOR cannot create Supervisor or Admin via /api/users/staff (403 Forbidden)")
    void testSupervisorCannotAccessStaffEndpoint() throws Exception {
        FirebaseToken mockToken = createMockFirebaseToken("uid-sup", "+919000000003");
        when(firebaseAuthService.verifyIdToken("sup-token")).thenReturn(mockToken);
        when(userRepository.findByMobileNumber("+91 9000000003")).thenReturn(Optional.of(mockSupervisor));

        mockMvc.perform(post("/api/users/staff")
                        .header("Authorization", "Bearer sup-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobileNumber\":\"9876543210\",\"name\":\"New Sup\",\"role\":\"SUPERVISOR\",\"departmentId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SUPERVISOR can fetch department workers via GET /api/users/workers")
    void testSupervisorCanGetWorkers() throws Exception {
        FirebaseToken mockToken = createMockFirebaseToken("uid-sup", "+919000000003");
        when(firebaseAuthService.verifyIdToken("sup-token")).thenReturn(mockToken);
        when(userRepository.findByMobileNumber("+91 9000000003")).thenReturn(Optional.of(mockSupervisor));
        when(userRepository.findByRoleAndDepartmentId(Role.WORKER, 1L)).thenReturn(List.of(mockWorker));

        mockMvc.perform(get("/api/users/workers")
                        .header("Authorization", "Bearer sup-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Authenticated user can register FCM token via POST /api/users/fcm-token")
    void testUpdateFcmToken() throws Exception {
        FirebaseToken mockToken = createMockFirebaseToken("uid-citizen", "+919000000001");
        when(firebaseAuthService.verifyIdToken("citizen-token")).thenReturn(mockToken);
        when(userRepository.findByMobileNumber("+91 9000000001")).thenReturn(Optional.of(mockCitizen));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        mockMvc.perform(post("/api/users/fcm-token")
                        .header("Authorization", "Bearer citizen-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcmToken\":\"sample-device-fcm-token\"}"))
                .andExpect(status().isOk());
    }
}
