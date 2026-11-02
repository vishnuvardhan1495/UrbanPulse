package com.urbanpulse.service;

import com.urbanpulse.entity.IssueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CategoryDepartmentMappingServiceTest {

    private CategoryDepartmentMappingService mappingService;

    @BeforeEach
    void setUp() {
        mappingService = new CategoryDepartmentMappingService();
    }

    @Test
    @DisplayName("Should map all 8 canonical civic complaint categories to exact departments")
    void testCanonicalCategoryDepartmentMappings() {
        assertEquals(Optional.of("Engineering Department"), mappingService.getDepartmentForCategory("Damaged Concrete Structures"));
        assertEquals(Optional.of("Electrical Department"), mappingService.getDepartmentForCategory("Damaged Electrical Poles"));
        assertEquals(Optional.of("Road Safety Department"), mappingService.getDepartmentForCategory("Damaged Road Signs"));
        assertEquals(Optional.of("Public Health Department"), mappingService.getDepartmentForCategory("Dead Animals Pollution"));
        assertEquals(Optional.of("Horticulture Department"), mappingService.getDepartmentForCategory("Fallen Trees"));
        assertEquals(Optional.of("Sanitation Department"), mappingService.getDepartmentForCategory("Garbage"));
        assertEquals(Optional.of("Traffic Department"), mappingService.getDepartmentForCategory("Illegal Parking"));
        assertEquals(Optional.of("Road Safety Department"), mappingService.getDepartmentForCategory("Potholes and Road Cracks"));
    }

    @Test
    @DisplayName("Should canonicalize raw Keras model output classes properly")
    void testRawModelClassCanonicalization() {
        assertEquals("Damaged Concrete Structures", mappingService.normalizeCategory("Damaged concrete structures"));
        assertEquals("Damaged Electrical Poles", mappingService.normalizeCategory("DamagedElectricalPoles"));
        assertEquals("Damaged Road Signs", mappingService.normalizeCategory("DamagedRoadSigns"));
        assertEquals("Dead Animals Pollution", mappingService.normalizeCategory("DeadAnimalsPollution"));
        assertEquals("Fallen Trees", mappingService.normalizeCategory("FallenTrees"));
        assertEquals("Garbage", mappingService.normalizeCategory("Garbage"));
        assertEquals("Illegal Parking", mappingService.normalizeCategory("IllegalParking"));
        assertEquals("Potholes and Road Cracks", mappingService.normalizeCategory("Potholes and RoadCracks"));
    }

    @Test
    @DisplayName("Should map raw Keras model output classes directly to departments")
    void testRawModelClassDepartmentMappings() {
        assertEquals(Optional.of("Engineering Department"), mappingService.getDepartmentForCategory("Damaged concrete structures"));
        assertEquals(Optional.of("Electrical Department"), mappingService.getDepartmentForCategory("DamagedElectricalPoles"));
        assertEquals(Optional.of("Road Safety Department"), mappingService.getDepartmentForCategory("DamagedRoadSigns"));
        assertEquals(Optional.of("Public Health Department"), mappingService.getDepartmentForCategory("DeadAnimalsPollution"));
        assertEquals(Optional.of("Horticulture Department"), mappingService.getDepartmentForCategory("FallenTrees"));
        assertEquals(Optional.of("Sanitation Department"), mappingService.getDepartmentForCategory("Garbage"));
        assertEquals(Optional.of("Traffic Department"), mappingService.getDepartmentForCategory("IllegalParking"));
        assertEquals(Optional.of("Road Safety Department"), mappingService.getDepartmentForCategory("Potholes and RoadCracks"));
    }

    @Test
    @DisplayName("Should map categories to appropriate IssueType enums")
    void testIssueTypeMappings() {
        assertEquals(IssueType.POTHOLE, mappingService.getIssueTypeForCategory("Potholes and Road Cracks"));
        assertEquals(IssueType.GARBAGE, mappingService.getIssueTypeForCategory("Garbage"));
        assertEquals(IssueType.STREET_LIGHT_DAMAGE, mappingService.getIssueTypeForCategory("Damaged Electrical Poles"));
        assertEquals(IssueType.INFRASTRUCTURE_DAMAGE, mappingService.getIssueTypeForCategory("Damaged Concrete Structures"));
        assertEquals(IssueType.NONE, mappingService.getIssueTypeForCategory("NonExistentCategory"));
    }
}
