package com.urbanpulse.config;

import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAccountInitializerTest {

    private UserRepository userRepository;
    private AdminAccountInitializer initializer;
    private final String CANONICAL_ADMIN = "+91 9966843779";
    private User unrelatedUser;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        initializer = new AdminAccountInitializer(userRepository, "9966843779");

        unrelatedUser = User.builder()
                .mobileNumber("+91 9876543210")
                .name("Unrelated Citizen")
                .role(Role.CITIZEN)
                .build();
    }

    @Test
    @DisplayName("1. Admin missing -> creates canonical admin")
    void testAdminMissing_CreatesCanonicalAdmin() {
        when(userRepository.findAll()).thenReturn(List.of(unrelatedUser));

        initializer.initializeAdminAccount();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        User savedUser = captor.getValue();
        assertEquals(CANONICAL_ADMIN, savedUser.getMobileNumber());
        assertEquals(Role.ADMIN, savedUser.getRole());
    }

    @Test
    @DisplayName("2. Canonical admin already exists -> does not create duplicate")
    void testCanonicalAdminExists_DoesNotCreateDuplicate() {
        User canonicalAdmin = User.builder()
                .mobileNumber(CANONICAL_ADMIN)
                .role(Role.ADMIN)
                .build();
        when(userRepository.findAll()).thenReturn(List.of(unrelatedUser, canonicalAdmin));

        initializer.initializeAdminAccount();

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("3. Existing admin role is wrong -> changes ONLY that configured admin to ADMIN")
    void testCanonicalAdminWrongRole_UpdatesToAdmin() {
        User canonicalCitizen = User.builder()
                .mobileNumber(CANONICAL_ADMIN)
                .role(Role.CITIZEN)
                .build();
        when(userRepository.findAll()).thenReturn(List.of(unrelatedUser, canonicalCitizen));

        initializer.initializeAdminAccount();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        assertEquals(CANONICAL_ADMIN, captor.getValue().getMobileNumber());
        assertEquals(Role.ADMIN, captor.getValue().getRole());
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("4. Legacy admin number exists as 9966843779 -> migrates to +91 9966843779")
    void testLegacyAdminExists_MigratesToCanonical() {
        User legacyAdmin = User.builder()
                .mobileNumber("9966843779")
                .role(Role.ADMIN)
                .build();
        when(userRepository.findAll()).thenReturn(List.of(unrelatedUser, legacyAdmin));

        initializer.initializeAdminAccount();

        verify(userRepository, times(1)).delete(legacyAdmin);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        assertEquals(CANONICAL_ADMIN, captor.getValue().getMobileNumber());
        assertEquals(Role.ADMIN, captor.getValue().getRole());
    }

    @Test
    @DisplayName("5. Running initializer twice -> same database state after both runs")
    void testIdempotency_RunningTwiceHasSameResult() {
        User canonicalAdmin = User.builder()
                .mobileNumber(CANONICAL_ADMIN)
                .role(Role.ADMIN)
                .build();
        when(userRepository.findAll()).thenReturn(List.of(canonicalAdmin));

        // First run
        initializer.initializeAdminAccount();
        // Second run
        initializer.initializeAdminAccount();

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("6. Unrelated users remain unchanged")
    void testUnrelatedUsersUnchanged() {
        when(userRepository.findAll()).thenReturn(List.of(unrelatedUser));

        initializer.initializeAdminAccount();

        verify(userRepository, never()).delete(unrelatedUser);
        verify(userRepository, never()).save(unrelatedUser);
    }
}
