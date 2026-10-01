package com.urbanpulse.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FcmTokenRequest {

    @NotBlank(message = "FCM token cannot be blank")
    private String fcmToken;
}
