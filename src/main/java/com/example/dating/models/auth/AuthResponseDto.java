package com.example.dating.models.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponseDto {
    private String token;
    private String userId;
    private String email;
    private String name;
    private String registrationStage;
    private boolean emailVerified;
    private String authProvider;
}
