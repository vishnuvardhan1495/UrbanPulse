package com.urbanpulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckDuplicateResponse {

    private boolean duplicateFound;
    private int matchCount;
    private Double radiusMeters;
    private List<DuplicateComplaintSummary> matches;
}
