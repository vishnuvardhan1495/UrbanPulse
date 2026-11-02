package com.urbanpulse.service;

import com.urbanpulse.dto.DepartmentRequest;
import com.urbanpulse.dto.DepartmentResponse;
import com.urbanpulse.entity.Department;
import com.urbanpulse.repository.DepartmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final CategoryDepartmentMappingService categoryDepartmentMappingService;

    public DepartmentService(DepartmentRepository departmentRepository,
                             CategoryDepartmentMappingService categoryDepartmentMappingService) {
        this.departmentRepository = departmentRepository;
        this.categoryDepartmentMappingService = categoryDepartmentMappingService;
    }

    public java.util.Map<String, String> getCategoryMappings() {
        return categoryDepartmentMappingService.getCategoryToDepartmentMap();
    }

    @Transactional
    public DepartmentResponse createDepartment(DepartmentRequest request) {
        if (departmentRepository.existsByName(request.getName().trim())) {
            throw new IllegalArgumentException("Department with name '" + request.getName() + "' already exists");
        }

        Department department = Department.builder()
                .name(request.getName().trim())
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .build();

        Department saved = departmentRepository.save(department);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> getAllDepartments() {
        return departmentRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DepartmentResponse getDepartmentById(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + id));
        return mapToResponse(department);
    }

    private DepartmentResponse mapToResponse(Department department) {
        return DepartmentResponse.builder()
                .id(department.getId())
                .name(department.getName())
                .description(department.getDescription())
                .build();
    }
}
