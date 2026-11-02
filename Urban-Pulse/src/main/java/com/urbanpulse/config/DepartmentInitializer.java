package com.urbanpulse.config;

import com.urbanpulse.entity.Department;
import com.urbanpulse.repository.DepartmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Startup initializer to ensure the 8 standard municipal departments exist in PostgreSQL.
 * Idempotent: checks for existing department names before inserting, ensuring no duplicates.
 */
@Component
@Order(2)
public class DepartmentInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentInitializer.class);

    private final DepartmentRepository departmentRepository;

    public DepartmentInitializer(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        initializeDepartments();
    }

    public void initializeDepartments() {
        Map<String, String> standardDepartments = new LinkedHashMap<>();
        standardDepartments.put("Engineering Department", "Damaged Concrete Structures");
        standardDepartments.put("Electrical Department", "Damaged Electrical Poles");
        standardDepartments.put("Road Safety Department", "Damaged Road Signs");
        standardDepartments.put("Public Health Department", "Dead Animals Pollution");
        standardDepartments.put("Horticulture Department", "Fallen Trees");
        standardDepartments.put("Sanitation Department", "Garbage");
        standardDepartments.put("Traffic Department", "Illegal Parking");
        standardDepartments.put("Roads and Infrastructure Department", "Potholes and Road Cracks");

        for (Map.Entry<String, String> entry : standardDepartments.entrySet()) {
            String deptName = entry.getKey();
            String description = entry.getValue();

            if (!departmentRepository.existsByName(deptName)) {
                logger.info("[DEPT-INIT] Creating missing standard department: {}", deptName);
                departmentRepository.save(Department.builder()
                        .name(deptName)
                        .description(description)
                        .build());
            } else {
                logger.debug("[DEPT-INIT] Department already exists: {}", deptName);
            }
        }
    }
}
