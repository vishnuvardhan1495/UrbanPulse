package com.urbanpulse.service;

import com.google.firebase.auth.FirebaseToken;
import com.urbanpulse.dto.AuthResponse;
import com.urbanpulse.dto.CreateStaffRequest;
import com.urbanpulse.dto.CreateWorkerRequest;
import com.urbanpulse.dto.UserResponse;
import com.urbanpulse.entity.Department;
import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.DepartmentRepository;
import com.urbanpulse.repository.UserRepository;
import com.urbanpulse.util.PhoneNumberUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        departmentRepository = Mockito.mock(DepartmentRepository.class);
        userService = new UserService(userRepository, departmentRepository);
    }

    @Test
    @DisplayName("loginOrRegister returns isNewUser = false when user already exists in PostgreSQL")
    void testExistingUserLogin() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-existing");
        Map<String, Object> claims = new HashMap<>();
        claims.put("phone_number", "+919876543210");
        when(mockToken.getClaims()).thenReturn(claims);

        String canonicalPhone = "+91 9876543210";

        User existingUser = User.builder()
                .mobileNumber(canonicalPhone)
                .name("Existing Citizen")
                .role(Role.CITIZEN)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.of(existingUser));

        AuthResponse response = userService.loginOrRegister(mockToken);

        assertNotNull(response);
        assertTrue(response.isAuthenticated());
        assertFalse(response.isNewUser());
        assertEquals("firebase-uid-existing", response.getFirebaseUid());
        assertEquals(canonicalPhone, response.getMobileNumber());
        assertEquals("CITIZEN", response.getRole());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("loginOrRegister creates and saves new user with role CITIZEN when user does not exist")
    void testNewUserRegistration() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-new");
        Map<String, Object> claims = new HashMap<>();
        claims.put("phone_number", "+919123456780");
        when(mockToken.getClaims()).thenReturn(claims);

        String canonicalPhone = "+91 9123456780";

        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.empty());

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User userToSave = invocation.getArgument(0);
            return User.builder()
                    .mobileNumber(userToSave.getMobileNumber())
                    .name(userToSave.getName())
                    .role(userToSave.getRole())
                    .createdAt(LocalDateTime.now())
                    .build();
        });

        AuthResponse response = userService.loginOrRegister(mockToken);

        assertNotNull(response);
        assertTrue(response.isAuthenticated());
        assertTrue(response.isNewUser());
        assertEquals("firebase-uid-new", response.getFirebaseUid());
        assertEquals(canonicalPhone, response.getMobileNumber());
        assertEquals("CITIZEN", response.getRole());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User savedUser = captor.getValue();
        assertEquals(canonicalPhone, savedUser.getMobileNumber());
        assertEquals(Role.CITIZEN, savedUser.getRole());
    }

    @Test
    @DisplayName("loginOrRegister throws IllegalArgumentException when token lacks phone_number claim")
    void testTokenWithoutPhoneNumberThrows() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-no-phone");
        when(mockToken.getClaims()).thenReturn(Collections.emptyMap());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> userService.loginOrRegister(mockToken)
        );

        assertTrue(exception.getMessage().contains("Firebase token does not contain a verified phone number"));
        verify(userRepository, never()).findByMobileNumber(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("loginOrRegister handles race-condition/concurrent registration with isNewUser = false")
    void testConcurrentRegistrationHandledAsExistingUser() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-concurrent");
        Map<String, Object> claims = new HashMap<>();
        claims.put("phone_number", "+919999888877");
        when(mockToken.getClaims()).thenReturn(claims);

        String canonicalPhone = "+91 9999888877";

        User concurrentlySavedUser = User.builder()
                .mobileNumber(canonicalPhone)
                .name("Citizen")
                .role(Role.CITIZEN)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByMobileNumber(canonicalPhone))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentlySavedUser));

        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        AuthResponse response = userService.loginOrRegister(mockToken);

        assertNotNull(response);
        assertTrue(response.isAuthenticated());
        assertFalse(response.isNewUser());
        assertEquals("firebase-uid-concurrent", response.getFirebaseUid());
        assertEquals(canonicalPhone, response.getMobileNumber());
        assertEquals("CITIZEN", response.getRole());

        verify(userRepository, times(2)).findByMobileNumber(canonicalPhone);
    }

    @Test
    @DisplayName("loginOrRegister preserves WORKER role for existing WORKER user")
    void testExistingWorkerLoginPreservesRole() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-worker");
        Map<String, Object> claims = new HashMap<>();
        claims.put("phone_number", "+919876543211");
        when(mockToken.getClaims()).thenReturn(claims);

        String canonicalPhone = "+91 9876543211";

        User existingWorker = User.builder()
                .mobileNumber(canonicalPhone)
                .name("Municipal Worker")
                .role(Role.WORKER)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.of(existingWorker));

        AuthResponse response = userService.loginOrRegister(mockToken);

        assertNotNull(response);
        assertEquals("WORKER", response.getRole());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("loginOrRegister preserves SUPERVISOR role for existing SUPERVISOR user")
    void testExistingSupervisorLoginPreservesRole() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-supervisor");
        Map<String, Object> claims = new HashMap<>();
        claims.put("phone_number", "+919876543212");
        when(mockToken.getClaims()).thenReturn(claims);

        String canonicalPhone = "+91 9876543212";

        User existingSupervisor = User.builder()
                .mobileNumber(canonicalPhone)
                .name("Area Supervisor")
                .role(Role.SUPERVISOR)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.of(existingSupervisor));

        AuthResponse response = userService.loginOrRegister(mockToken);

        assertNotNull(response);
        assertEquals("SUPERVISOR", response.getRole());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("loginOrRegister preserves ADMIN role for existing ADMIN user")
    void testExistingAdminLoginPreservesRole() {
        FirebaseToken mockToken = Mockito.mock(FirebaseToken.class);
        when(mockToken.getUid()).thenReturn("firebase-uid-admin");
        Map<String, Object> claims = new HashMap<>();
        claims.put("phone_number", "+919876543213");
        when(mockToken.getClaims()).thenReturn(claims);

        String canonicalPhone = "+91 9876543213";

        User existingAdmin = User.builder()
                .mobileNumber(canonicalPhone)
                .name("Platform Admin")
                .role(Role.ADMIN)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.of(existingAdmin));

        AuthResponse response = userService.loginOrRegister(mockToken);

        assertNotNull(response);
        assertEquals("ADMIN", response.getRole());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("createCitizenIfNotExists creates new CITIZEN user when not found")
    void testCreateCitizenIfNotExistsNew() {
        String canonicalPhone = "+91 9000000000";
        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User user = userService.createCitizenIfNotExists("9000000000");

        assertNotNull(user);
        assertEquals(canonicalPhone, user.getMobileNumber());
        assertEquals(Role.CITIZEN, user.getRole());
        assertNull(user.getName());
    }

    @Test
    @DisplayName("getOrCreateCitizen returns existing user without modifying role")
    void testGetOrCreateCitizenExisting() {
        String canonicalPhone = "+91 9000000001";
        User existingAdmin = User.builder()
                .mobileNumber(canonicalPhone)
                .role(Role.ADMIN)
                .build();

        when(userRepository.findByMobileNumber(canonicalPhone)).thenReturn(Optional.of(existingAdmin));

        User user = userService.getOrCreateCitizen("9000000001");

        assertNotNull(user);
        assertEquals(Role.ADMIN, user.getRole());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("createStaffUserByAdmin successfully creates SUPERVISOR staff account")
    void testAdminCreateSupervisorSuccess() {
        String canonicalPhone = "+91 9876500001";
        CreateStaffRequest request = CreateStaffRequest.builder()
                .mobileNumber("9876500001")
                .name("New Supervisor")
                .role("SUPERVISOR")
                .departmentId(1L)
                .build();

        Department dept = Department.builder()
                .id(1L)
                .name("ROADS")
                .build();

        when(userRepository.existsById(canonicalPhone)).thenReturn(false);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(dept));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        UserResponse response = userService.createStaffUserByAdmin(request);

        assertNotNull(response);
        assertEquals(canonicalPhone, response.getMobileNumber());
        assertEquals("SUPERVISOR", response.getRole());
        assertEquals("ROADS", response.getDepartmentName());
    }

    @Test
    @DisplayName("createStaffUserByAdmin rejects creating ADMIN or CITIZEN staff accounts")
    void testAdminCreateInvalidRoleThrows() {
        CreateStaffRequest request = CreateStaffRequest.builder()
                .mobileNumber("9876500002")
                .name("Unauthorized Admin Attempt")
                .role("ADMIN")
                .departmentId(1L)
                .build();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> userService.createStaffUserByAdmin(request)
        );

        assertTrue(ex.getMessage().contains("Admin can only create SUPERVISOR or WORKER staff accounts"));
    }

    @Test
    @DisplayName("createWorkerBySupervisor automatically assigns supervisor's department to new worker")
    void testSupervisorCreateWorkerSuccess() {
        String canonicalSupervisorPhone = "+91 9876500099";
        String canonicalWorkerPhone = "+91 9876500003";

        CreateWorkerRequest request = CreateWorkerRequest.builder()
                .mobileNumber("9876500003")
                .name("Road Worker")
                .build();

        Department roadsDept = Department.builder()
                .id(10L)
                .name("ROADS")
                .build();

        User supervisor = User.builder()
                .mobileNumber(canonicalSupervisorPhone)
                .role(Role.SUPERVISOR)
                .department(roadsDept)
                .build();

        when(userRepository.findByMobileNumber(canonicalSupervisorPhone)).thenReturn(Optional.of(supervisor));
        when(userRepository.existsById(canonicalWorkerPhone)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        UserResponse response = userService.createWorkerBySupervisor("9876500099", request);

        assertNotNull(response);
        assertEquals(canonicalWorkerPhone, response.getMobileNumber());
        assertEquals("WORKER", response.getRole());
        assertEquals(10L, response.getDepartmentId());
        assertEquals("ROADS", response.getDepartmentName());
    }
}
