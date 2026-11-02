package com.urbanpulse.service;

import com.urbanpulse.entity.IssueType;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service providing the strict category-to-department mapping and canonicalization
 * for civic complaints classified by the ML model.
 *
 * Mappings:
 *  - Damaged Concrete Structures -> Engineering Department
 *  - Damaged Electrical Poles    -> Electrical Department
 *  - Damaged Road Signs          -> Road Safety Department
 *  - Dead Animals Pollution      -> Public Health Department
 *  - Fallen Trees                -> Horticulture Department
 *  - Garbage                     -> Sanitation Department
 *  - Illegal Parking             -> Traffic Department
 *  - Potholes and Road Cracks    -> Road Safety Department
 */
@Service
public class CategoryDepartmentMappingService {

    private static final Map<String, String> CATEGORY_TO_DEPARTMENT_MAP;
    private static final Map<String, String> RAW_TO_CANONICAL_MAP;
    private static final Map<String, IssueType> CATEGORY_TO_ISSUE_TYPE_MAP;

    static {
        // Raw model class name -> Canonical display name
        Map<String, String> rawMap = new LinkedHashMap<>();
        rawMap.put("Damaged concrete structures", "Damaged Concrete Structures");
        rawMap.put("Damaged Concrete Structures", "Damaged Concrete Structures");
        rawMap.put("DamagedElectricalPoles", "Damaged Electrical Poles");
        rawMap.put("Damaged Electrical Poles", "Damaged Electrical Poles");
        rawMap.put("DamagedRoadSigns", "Damaged Road Signs");
        rawMap.put("Damaged Road Signs", "Damaged Road Signs");
        rawMap.put("DeadAnimalsPollution", "Dead Animals Pollution");
        rawMap.put("Dead Animals Pollution", "Dead Animals Pollution");
        rawMap.put("FallenTrees", "Fallen Trees");
        rawMap.put("Fallen Trees", "Fallen Trees");
        rawMap.put("Garbage", "Garbage");
        rawMap.put("IllegalParking", "Illegal Parking");
        rawMap.put("Illegal Parking", "Illegal Parking");
        rawMap.put("Potholes and RoadCracks", "Potholes and Road Cracks");
        rawMap.put("Potholes and Road Cracks", "Potholes and Road Cracks");
        RAW_TO_CANONICAL_MAP = Collections.unmodifiableMap(rawMap);

        // Canonical category name -> Responsible Department
        Map<String, String> deptMap = new LinkedHashMap<>();
        deptMap.put("Damaged Concrete Structures", "Engineering Department");
        deptMap.put("Damaged Electrical Poles", "Electrical Department");
        deptMap.put("Damaged Road Signs", "Road Safety Department");
        deptMap.put("Dead Animals Pollution", "Public Health Department");
        deptMap.put("Fallen Trees", "Horticulture Department");
        deptMap.put("Garbage", "Sanitation Department");
        deptMap.put("Illegal Parking", "Traffic Department");
        deptMap.put("Potholes and Road Cracks", "Road Safety Department");
        CATEGORY_TO_DEPARTMENT_MAP = Collections.unmodifiableMap(deptMap);

        // Category -> closest IssueType enum
        Map<String, IssueType> issueMap = new LinkedHashMap<>();
        issueMap.put("Damaged Concrete Structures", IssueType.INFRASTRUCTURE_DAMAGE);
        issueMap.put("Damaged Electrical Poles", IssueType.STREET_LIGHT_DAMAGE);
        issueMap.put("Damaged Road Signs", IssueType.INFRASTRUCTURE_DAMAGE);
        issueMap.put("Dead Animals Pollution", IssueType.DRAINAGE_ISSUE);
        issueMap.put("Fallen Trees", IssueType.INFRASTRUCTURE_DAMAGE);
        issueMap.put("Garbage", IssueType.GARBAGE);
        issueMap.put("Illegal Parking", IssueType.INFRASTRUCTURE_DAMAGE);
        issueMap.put("Potholes and Road Cracks", IssueType.POTHOLE);
        CATEGORY_TO_ISSUE_TYPE_MAP = Collections.unmodifiableMap(issueMap);
    }

    /**
     * Returns an unmodifiable map of canonical complaint categories to their responsible department names.
     */
    public Map<String, String> getCategoryToDepartmentMap() {
        return CATEGORY_TO_DEPARTMENT_MAP;
    }

    /**
     * Converts raw ML output class names into clean canonical title-case strings.
     */
    public String normalizeCategory(String rawCategory) {
        if (rawCategory == null || rawCategory.trim().isEmpty()) {
            return "Unknown";
        }
        String trimmed = rawCategory.trim();
        if (RAW_TO_CANONICAL_MAP.containsKey(trimmed)) {
            return RAW_TO_CANONICAL_MAP.get(trimmed);
        }
        // Case-insensitive fallback
        for (Map.Entry<String, String> entry : RAW_TO_CANONICAL_MAP.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(trimmed)) {
                return entry.getValue();
            }
        }
        return trimmed;
    }

    /**
     * Looks up the responsible department for a given raw or canonical category string.
     */
    public Optional<String> getDepartmentForCategory(String category) {
        if (category == null) {
            return Optional.empty();
        }
        String canonical = normalizeCategory(category);
        return Optional.ofNullable(CATEGORY_TO_DEPARTMENT_MAP.get(canonical));
    }

    /**
     * Maps a category string to the corresponding IssueType enum value.
     */
    public IssueType getIssueTypeForCategory(String category) {
        if (category == null) {
            return IssueType.NONE;
        }
        String canonical = normalizeCategory(category);
        return CATEGORY_TO_ISSUE_TYPE_MAP.getOrDefault(canonical, IssueType.NONE);
    }
}
