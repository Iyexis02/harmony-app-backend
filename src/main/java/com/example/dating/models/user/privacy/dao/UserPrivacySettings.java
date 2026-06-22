package com.example.dating.models.user.privacy.dao;

import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@Setter
@ToString
@Table(name = "user_privacy_settings", indexes = {
    @Index(name = "idx_privacy_user", columnList = "user_id")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPrivacySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ToString.Exclude
    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(name = "is_profile_public", nullable = false)
    @Builder.Default
    private Boolean isProfilePublic = true;

    @Column(name = "show_age", nullable = false)
    @Builder.Default
    private Boolean showAge = true;

    @Column(name = "show_distance", nullable = false)
    @Builder.Default
    private Boolean showDistance = true;

    @Column(name = "show_last_active", nullable = false)
    @Builder.Default
    private Boolean showLastActive = true;

    @Column(name = "discoverable", nullable = false)
    @Builder.Default
    private Boolean discoverable = true;

    // Allow others to see if you liked them
    @Column(name = "show_liked_by_you", nullable = false)
    @Builder.Default
    private Boolean showLikedByYou = false;

    // Show Spotify profile publicly
    @Column(name = "show_spotify_profile", nullable = false)
    @Builder.Default
    private Boolean showSpotifyProfile = true;

    // Show top artists/tracks publicly
    @Column(name = "show_music_stats", nullable = false)
    @Builder.Default
    private Boolean showMusicStats = true;

    // Incognito mode - browse without being seen
    @Column(name = "incognito_mode", nullable = false)
    @Builder.Default
    private Boolean incognitoMode = false;

    // Read receipts for messages
    @Column(name = "read_receipts", nullable = false)
    @Builder.Default
    private Boolean readReceipts = true;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserPrivacySettings other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
