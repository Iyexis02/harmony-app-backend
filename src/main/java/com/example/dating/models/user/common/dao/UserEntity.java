package com.example.dating.models.user.common.dao;

import com.example.dating.enums.user.*;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import com.example.dating.models.user.personality.dao.UserPersonality;
import com.example.dating.models.user.photos.dao.UserPhoto;
import com.example.dating.models.user.preferences.dao.UserMusicPreferences;
import com.example.dating.models.user.privacy.dao.UserPrivacySettings;
import jakarta.persistence.*;
import jakarta.validation.Constraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.UniqueElements;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@Table(name = "users", indexes = {
        @Index(name = "idx_spotify_id", columnList = "spotify_id"),
        @Index(name = "idx_location", columnList = "location_lat,location_lon"),
        @Index(name = "idx_registration_stage", columnList = "registration_stage"),
        @Index(name = "idx_premium_status", columnList = "premium_status"),
        @Index(name = "idx_auth_provider", columnList = "auth_provider"),
        @Index(name = "idx_email_verification_token", columnList = "email_verification_token"),
        @Index(name = "idx_password_reset_token", columnList = "password_reset_token")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "email", unique = true, nullable = false, length = 255)
    private String email;

    // Authentication
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.SPOTIFY;

    @Column(name = "password_hash", length = 60)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "email_verification_token", length = 255)
    private String emailVerificationToken;

    @Column(name = "email_verification_expires")
    private LocalDateTime emailVerificationExpires;

    @Column(name = "password_reset_token", length = 255)
    private String passwordResetToken;

    @Column(name = "password_reset_expires")
    private LocalDateTime passwordResetExpires;

    // Spotify Integration
    @Column(name = "spotify_id", unique = true, length = 255, nullable = true)
    private String spotifyId;

    @Column(name = "access_token", columnDefinition = "TEXT")
    private String spotifyAccessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String spotifyRefreshToken;

    @Column(name = "spotify_token_expires")
    private Instant spotifyTokenExpires;

    @Column(name = "last_spotify_sync_at")
    private LocalDateTime lastSpotifySyncAt;

    // Basic Profile Information
    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "dob")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 50)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "sexual_orientation", length = 50)
    private SexualOrientation sexualOrientation;

    // Location Information
    @Column(name = "location_lat", precision = 10, scale = 7)
    private BigDecimal locationLat;

    @Column(name = "location_lon", precision = 10, scale = 7)
    private BigDecimal locationLon;

    @Column(name = "location_city", length = 100)
    private String locationCity;

    @Column(name = "location_country", length = 100)
    private String locationCountry;

    // Additional Profile Details
    @Column(name = "language", length = 50)
    private String language;

    // Relationships to other entities
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserMusicPreferences musicPreferences;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserLifestyle lifestyle;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserPersonality personality;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserDatingPreferences datingPreferences;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserPrivacySettings privacySettings;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<UserPhoto> photos = new ArrayList<>();

    // Registration & Status
    @Enumerated(EnumType.STRING)
    @Column(name = "registration_stage", nullable = false)
    @Builder.Default
    private RegistrationStage registrationStage = RegistrationStage.STARTED;

    @Column(name = "premium_status", nullable = false)
    @Builder.Default
    private Boolean premiumStatus = false;

    @Column(name = "subscription_expires")
    private LocalDateTime subscriptionExpires;

    // Profile Settings
    @Column(name = "profile_completion_score")
    @Builder.Default
    private Integer profileCompletionScore = 0;

    // Cache Management (for matching algorithm optimization)
    @Column(name = "cache_version")
    @Builder.Default
    private Integer cacheVersion = 1;

    // Timestamps
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Helper Methods
    public boolean isSpotifyConnected() {
        return spotifyId != null && spotifyAccessToken != null;
    }

    public boolean isSpotifyTokenValid() {
        return spotifyTokenExpires != null &&
                spotifyTokenExpires.isAfter(Instant.now());
    }

    public boolean isPremiumActive() {
        return premiumStatus &&
                (subscriptionExpires == null ||
                        subscriptionExpires.isAfter(LocalDateTime.now()));
    }

    public Integer getAge() {
        if (dateOfBirth == null) {
            return null;
        }
        return LocalDate.now().getYear() - dateOfBirth.getYear();
    }

    public boolean isEmailUser() {
        return authProvider == AuthProvider.EMAIL;
    }

    public boolean canConnectSpotify() {
        return isEmailUser() && spotifyId == null;
    }
}