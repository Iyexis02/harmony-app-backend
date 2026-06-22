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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.hibernate.validator.constraints.UniqueElements;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Getter
@Setter
@ToString
@Table(name = "users", indexes = {
        @Index(name = "idx_spotify_id", columnList = "spotify_id"),
        @Index(name = "idx_location", columnList = "location_lat,location_lon"),
        @Index(name = "idx_registration_stage", columnList = "registration_stage"),
        @Index(name = "idx_premium_status", columnList = "premium_status"),
        @Index(name = "idx_auth_provider", columnList = "auth_provider"),
        @Index(name = "idx_email_verification_token", columnList = "email_verification_token"),
        @Index(name = "idx_password_reset_token", columnList = "password_reset_token"),
        @Index(name = "idx_email_verification_token_hash", columnList = "email_verification_token_hash"),
        @Index(name = "idx_password_reset_token_hash", columnList = "password_reset_token_hash"),
        // Batch C: age-range filter in findCandidateUsers()
        @Index(name = "idx_dob", columnList = "dob"),
        // Batch C: gender filter (DB-side push planned in Batch D)
        @Index(name = "idx_gender", columnList = "gender")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

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

    // SHA-256 hashes of the tokens — used for all lookups after Batch B.
    // The plaintext columns above are kept in the schema but are written as null for new tokens.
    @Column(name = "email_verification_token_hash", length = 64)
    private String emailVerificationTokenHash;

    @Column(name = "password_reset_token_hash", length = 64)
    private String passwordResetTokenHash;

    // Incremented on password reset to invalidate all outstanding JWTs.
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Integer tokenVersion = 0;

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
    // Batch D: @BatchSize reduces 500 individual SELECTs to ~10 batched IN-clause SELECTs
    // during findPotentialMatches() candidate scoring. Do NOT parallelize the scoring loop
    // without revisiting connection-pool exhaustion (batch loading holds connections open).
    @ToString.Exclude
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserMusicPreferences musicPreferences;

    @ToString.Exclude
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserLifestyle lifestyle;

    @ToString.Exclude
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserPersonality personality;

    @ToString.Exclude
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserDatingPreferences datingPreferences;

    @ToString.Exclude
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private UserPrivacySettings privacySettings;

    @ToString.Exclude
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    @Builder.Default
    private List<UserPhoto> photos = new ArrayList<>();

    // Master Batch E: Soft-delete guard used during account deletion.
    // Set to true by deleteAccount() before cleaning up child entities so that
    // concurrent swipes targeting this user are rejected before any FK insert.
    // The user row is still physically deleted at the end of the deletion transaction.
    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    // Per-account login lockout fields.
    // failedLoginAttempts is incremented on each password mismatch and reset on success.
    // lockedUntil is set to now+15min when attempts reach 5; null means not locked.
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

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
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    public boolean isEmailUser() {
        return authProvider == AuthProvider.EMAIL;
    }

    public boolean canConnectSpotify() {
        return isEmailUser() && spotifyId == null;
    }

    // ── Batch G: Bidirectional relationship helpers ───────────────────────────
    // These custom setters override the Lombok-generated ones for relationship
    // fields and sync both sides of each association so the in-memory object
    // graph stays consistent with what JPA will actually persist (the owning side
    // is the sub-entity, not UserEntity, because of mappedBy).
    //
    // WARNING: calling setXxx(null) with orphanRemoval = true will delete the
    //          existing DB row. Only pass null when intentionally removing the
    //          sub-entity record.

    public void setMusicPreferences(UserMusicPreferences musicPreferences) {
        this.musicPreferences = musicPreferences;
        if (musicPreferences != null) musicPreferences.setUser(this);
    }

    public void setLifestyle(UserLifestyle lifestyle) {
        this.lifestyle = lifestyle;
        if (lifestyle != null) lifestyle.setUser(this);
    }

    public void setPersonality(UserPersonality personality) {
        this.personality = personality;
        if (personality != null) personality.setUser(this);
    }

    public void setDatingPreferences(UserDatingPreferences datingPreferences) {
        this.datingPreferences = datingPreferences;
        if (datingPreferences != null) datingPreferences.setUser(this);
    }

    public void setPrivacySettings(UserPrivacySettings privacySettings) {
        this.privacySettings = privacySettings;
        if (privacySettings != null) privacySettings.setUser(this);
    }

    /**
     * Adds a photo and sets the back-reference on the photo.
     * Prefer this over {@code getPhotos().add(photo)} to keep both sides in sync.
     */
    public void addPhoto(UserPhoto photo) {
        photos.add(photo);
        photo.setUser(this);
    }

    /**
     * Removes a photo and clears its back-reference.
     * Prefer this over {@code getPhotos().remove(photo)}.
     */
    public void removePhoto(UserPhoto photo) {
        photos.remove(photo);
        photo.setUser(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserEntity other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}