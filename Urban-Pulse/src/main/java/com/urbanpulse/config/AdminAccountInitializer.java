package com.urbanpulse.config;

import com.urbanpulse.entity.Role;
import com.urbanpulse.entity.User;
import com.urbanpulse.repository.UserRepository;
import com.urbanpulse.util.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Startup initializer to ensure the configured admin account exists in PostgreSQL
 * with the canonical mobile number format ("+91 9966843779") and role ADMIN.
 * Idempotent and performs safe legacy format migration without modifying unrelated users.
 */
@Component
public class AdminAccountInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository userRepository;
    private final String rawAdminMobileNumber;

    public AdminAccountInitializer(
            UserRepository userRepository,
            @Value("${admin.mobile-number:9966843779}") String rawAdminMobileNumber) {
        this.userRepository = userRepository;
        this.rawAdminMobileNumber = rawAdminMobileNumber;
    }

    @Override
    @Transactional
    public void run(String... args) {
        initializeAdminAccount();
    }

    public void initializeAdminAccount() {
        if (rawAdminMobileNumber == null || rawAdminMobileNumber.trim().isEmpty()) {
            logger.warn("Admin mobile number is not configured.");
            return;
        }

        String canonicalAdminNumber = PhoneNumberUtil.normalize(rawAdminMobileNumber);

        // Find potential legacy representations of the admin number
        List<User> allUsers = userRepository.findAll();
        List<User> matchingAdminUsers = allUsers.stream()
                .filter(u -> {
                    try {
                        return PhoneNumberUtil.normalize(u.getMobileNumber()).equals(canonicalAdminNumber);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();

        Optional<User> canonicalUserOpt = matchingAdminUsers.stream()
                .filter(u -> u.getMobileNumber().equals(canonicalAdminNumber))
                .findFirst();

        List<User> legacyUsers = matchingAdminUsers.stream()
                .filter(u -> !u.getMobileNumber().equals(canonicalAdminNumber))
                .toList();

        if (canonicalUserOpt.isPresent()) {
            User canonicalUser = canonicalUserOpt.get();
            if (canonicalUser.getRole() != Role.ADMIN) {
                logger.info("[ADMIN-INIT] Updating existing user {} role from {} to ADMIN", canonicalUser.getMobileNumber(), canonicalUser.getRole());
                canonicalUser.setRole(Role.ADMIN);
                userRepository.save(canonicalUser);
            } else {
                logger.info("[ADMIN-INIT] Admin account {} verified in PostgreSQL with role ADMIN", canonicalUser.getMobileNumber());
            }

            // Safely cleanup duplicate legacy admin record if any exists
            for (User legacy : legacyUsers) {
                logger.info("[ADMIN-INIT] Cleaning up duplicate legacy admin record {}", legacy.getMobileNumber());
                userRepository.delete(legacy);
            }
        } else if (!legacyUsers.isEmpty()) {
            // Legacy record exists but canonical record does not -> Migrate legacy record to canonical
            User primaryLegacy = legacyUsers.get(0);
            logger.info("[ADMIN-INIT] Migrating legacy admin user {} to canonical {}", primaryLegacy.getMobileNumber(), canonicalAdminNumber);

            for (User legacy : legacyUsers) {
                userRepository.delete(legacy);
            }

            User canonicalAdmin = User.builder()
                    .mobileNumber(canonicalAdminNumber)
                    .name(primaryLegacy.getName() != null ? primaryLegacy.getName() : "System Admin")
                    .role(Role.ADMIN)
                    .department(primaryLegacy.getDepartment())
                    .build();
            userRepository.save(canonicalAdmin);
        } else {
            // No record exists at all -> Create new canonical admin
            logger.info("[ADMIN-INIT] Creating new ADMIN account with canonical mobile number {}", canonicalAdminNumber);
            User newAdmin = User.builder()
                    .mobileNumber(canonicalAdminNumber)
                    .name("System Admin")
                    .role(Role.ADMIN)
                    .build();
            userRepository.save(newAdmin);
        }
    }
}
