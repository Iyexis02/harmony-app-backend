package com.example.dating.models.user.domain;

import com.example.dating.enums.user.AuthProvider;
import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RegistrationStage;
import com.example.dating.enums.user.SexualOrientation;
import com.example.dating.models.user.common.dao.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String email;

    // Authentication
    private AuthProvider authProvider;
    private String passwordHash;
    private Boolean emailVerified;
    private String emailVerificationToken;
    private LocalDateTime emailVerificationExpires;
    private String passwordResetToken;
    private LocalDateTime passwordResetExpires;
    private String emailVerificationTokenHash;
    private String passwordResetTokenHash;
    private Integer tokenVersion;

    // Spotify Integration
    private String spotifyId;
    private String spotifyAccessToken;
    private String spotifyRefreshToken;
    private Instant spotifyTokenExpires;

    private String imageUrl;
    private RegistrationStage registrationStage;
    private LocalDateTime createdAt;

    //Location
    private BigDecimal locationLat;
    private BigDecimal locationLon;
    private String locationCity;
    private String locationCountry;

    //BasicProfileInfo
    private String name;
    private LocalDate dateOfBirth;
    private Gender gender;
    private SexualOrientation sexualOrientation;

    // Batch E: Account lockout
    private Integer failedLoginAttempts;
    private LocalDateTime lockedUntil;

    // Reference to underlying entity for relationships
    private UserEntity userEntity;

    // Business logic methods
    public boolean isRegistrationComplete() {
        return registrationStage == RegistrationStage.FINISHED;
    }

    public boolean isSpotifyTokenExpired() {
        return spotifyTokenExpires != null &&
                Instant.now().isAfter(spotifyTokenExpires);
    }
}