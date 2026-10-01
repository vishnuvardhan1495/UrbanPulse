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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    public UserService(UserRepository userRepository, DepartmentRepository departmentRepository) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByMobileNumber(String mobileNumber) {
        if (mobileNumber == null || mobileNumber.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            String normalized = PhoneNumberUtil.normalize(mobileNumber);
            return userRepository.findByMobileNumber(normalized);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Transactional
    public User createCitizenIfNotExists(String mobileNumber) {
        String normalized = PhoneNumberUtil.normalize(mobileNumber);
        Optional<User> existingUserOpt = userRepository.findByMobileNumber(normalized);
        if (existingUserOpt.isPresent()) {
            return existingUserOpt.get();
        }

        User newUser = User.builder()
                .mobileNumber(normalized)
                .name(null)
                .role(Role.CITIZEN)
                .build();

        try {
            return userRepository.save(newUser);
        } catch (DataIntegrityViolationException ex) {
            logger.warn("Concurrent creation detected for mobile number {}; falling back to database record", normalized);
            return userRepository.findByMobileNumber(normalized)
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional
    public User getOrCreateCitizen(String mobileNumber) {
        return createCitizenIfNotExists(mobileNumber);
    }

    @Transactional
    public UserResponse createStaffUserByAdmin(CreateStaffRequest request) {
        String roleStr = request.getRole() != null ? request.getRole().trim().toUpperCase() : "";
        if (!roleStr.equals("SUPERVISOR") && !roleStr.equals("WORKER")) {
            throw new IllegalArgumentException("Admin can only create SUPERVISOR or WORKER staff accounts");
        }

        String normalizedMobile = PhoneNumberUtil.normalize(request.getMobileNumber());
        if (userRepository.existsById(normalizedMobile)) {
            throw new IllegalArgumentException("User with mobile number " + normalizedMobile + " already exists");
        }

        Department department = departmentRepository.findById(request.getDepartmentId())
                .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + request.getDepartmentId()));

        Role role = Role.valueOf(roleStr);

        User staffUser = User.builder()
                .mobileNumber(normalizedMobile)
                .name(request.getName() != null ? request.getName().trim() : null)
                .role(role)
                .department(department)
                .build();

        User saved = userRepository.save(staffUser);
        logger.info("Admin created staff user ({}) with mobile number {}", role, normalizedMobile);
        return mapToUserResponse(saved);
    }

    @Transactional
    public UserResponse createWorkerBySupervisor(String supervisorMobileNumber, CreateWorkerRequest request) {
        String normalizedSupervisor = PhoneNumberUtil.normalize(supervisorMobileNumber);
        User supervisor = userRepository.findByMobileNumber(normalizedSupervisor)
                .orElseThrow(() -> new IllegalArgumentException("Supervisor user record not found in database"));

        if (supervisor.getRole() != Role.SUPERVISOR) {
            throw new AccessDeniedException("Authenticated user is not a SUPERVISOR");
        }

        Department department = supervisor.getDepartment();
        if (department == null) {
            throw new IllegalStateException("Supervisor is not assigned to any department");
        }

        String normalizedWorkerMobile = PhoneNumberUtil.normalize(request.getMobileNumber());
        if (userRepository.existsById(normalizedWorkerMobile)) {
            throw new IllegalArgumentException("User with mobile number " + normalizedWorkerMobile + " already exists");
        }

        User workerUser = User.builder()
                .mobileNumber(normalizedWorkerMobile)
                .name(request.getName() != null ? request.getName().trim() : null)
                .role(Role.WORKER)
                .department(department)
                .build();

        User saved = userRepository.save(workerUser);
        logger.info("Supervisor {} created worker with mobile number {} under department {}",
                normalizedSupervisor, normalizedWorkerMobile, department.getName());
        return mapToUserResponse(saved);
    }

    @Transactional
    public UserResponse updateFcmToken(String mobileNumber, String fcmToken) {
        String normalizedMobile = PhoneNumberUtil.normalize(mobileNumber);
        User user = userRepository.findByMobileNumber(normalizedMobile)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + normalizedMobile));

        user.setFcmToken(fcmToken != null ? fcmToken.trim() : null);
        User saved = userRepository.save(user);
        logger.info("Updated FCM device registration token for user {}", normalizedMobile);
        return mapToUserResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getDepartmentWorkers(String supervisorMobileNumber) {
        String normalizedSupervisor = PhoneNumberUtil.normalize(supervisorMobileNumber);
        User supervisor = userRepository.findByMobileNumber(normalizedSupervisor)
                .orElseThrow(() -> new IllegalArgumentException("Supervisor not found: " + normalizedSupervisor));

        if (supervisor.getRole() != Role.SUPERVISOR) {
            throw new AccessDeniedException("Only SUPERVISOR can access department workers via this method");
        }

        if (supervisor.getDepartment() == null) {
            throw new IllegalStateException("Supervisor is not assigned to any department");
        }

        return userRepository.findByRoleAndDepartmentId(Role.WORKER, supervisor.getDepartment().getId())
                .stream()
                .map(this::mapToUserResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllWorkers() {
        return userRepository.findByRole(Role.WORKER)
                .stream()
                .map(this::mapToUserResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public AuthResponse loginOrRegister(FirebaseToken token) {
        if (token == null) {
            throw new IllegalArgumentException("Firebase token cannot be null");
        }

        String firebaseUid = token.getUid();
        Map<String, Object> claims = token.getClaims();

        String phoneNumber = null;
        if (claims != null && claims.containsKey("phone_number")) {
            Object phoneObj = claims.get("phone_number");
            if (phoneObj != null) {
                phoneNumber = phoneObj.toString().trim();
            }
        }

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            logger.warn("Authentication rejected: Firebase token for UID {} lacks a verified phone number", firebaseUid);
            throw new IllegalArgumentException("Firebase token does not contain a verified phone number");
        }

        String normalizedPhone = PhoneNumberUtil.normalize(phoneNumber);

        Optional<User> existingUserOpt = userRepository.findByMobileNumber(normalizedPhone);

        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            logger.info("Existing user logged in successfully for UID: {} (Role: {})", firebaseUid, existingUser.getRole());
            return AuthResponse.builder()
                    .authenticated(true)
                    .isNewUser(false)
                    .firebaseUid(firebaseUid)
                    .mobileNumber(existingUser.getMobileNumber())
                    .name(existingUser.getName())
                    .role(existingUser.getRole().name())
                    .departmentId(existingUser.getDepartment() != null ? existingUser.getDepartment().getId() : null)
                    .departmentName(existingUser.getDepartment() != null ? existingUser.getDepartment().getName() : null)
                    .build();
        }

        String name = null;
        if (claims != null && claims.containsKey("name")) {
            Object nameObj = claims.get("name");
            if (nameObj != null && !nameObj.toString().trim().isEmpty()) {
                name = nameObj.toString().trim();
            }
        }

        User newUser = User.builder()
                .mobileNumber(normalizedPhone)
                .name(name)
                .role(Role.CITIZEN)
                .build();

        try {
            User savedUser = userRepository.save(newUser);
            logger.info("New citizen registered successfully for UID: {}", firebaseUid);
            return AuthResponse.builder()
                    .authenticated(true)
                    .isNewUser(true)
                    .firebaseUid(firebaseUid)
                    .mobileNumber(savedUser.getMobileNumber())
                    .name(savedUser.getName())
                    .role(savedUser.getRole().name())
                    .departmentId(null)
                    .departmentName(null)
                    .build();
        } catch (DataIntegrityViolationException ex) {
            logger.warn("Concurrent registration detected for mobile number; falling back to existing user");
            User concurrentUser = userRepository.findByMobileNumber(normalizedPhone)
                    .orElseThrow(() -> ex);

            return AuthResponse.builder()
                    .authenticated(true)
                    .isNewUser(false)
                    .firebaseUid(firebaseUid)
                    .mobileNumber(concurrentUser.getMobileNumber())
                    .name(concurrentUser.getName())
                    .role(concurrentUser.getRole().name())
                    .departmentId(concurrentUser.getDepartment() != null ? concurrentUser.getDepartment().getId() : null)
                    .departmentName(concurrentUser.getDepartment() != null ? concurrentUser.getDepartment().getName() : null)
                    .build();
        }
    }

    public UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .mobileNumber(user.getMobileNumber())
                .name(user.getName())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
                .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : null)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
