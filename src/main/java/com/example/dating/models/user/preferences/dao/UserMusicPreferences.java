package com.example.dating.models.user.preferences.dao;

import com.example.dating.enums.user.ConcertFrequency;
import com.example.dating.enums.user.MusicImportance;
import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@Table(name = "user_music_preferences")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMusicPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    // Favorite music genres (stored as comma-separated or JSON)
    @Column(name = "favorite_genres", columnDefinition = "TEXT")
    private String favoriteGenres;

    @Enumerated(EnumType.STRING)
    @Column(name = "concert_frequency", length = 50)
    private ConcertFrequency concertFrequency;

    @Enumerated(EnumType.STRING)
    @Column(name = "music_importance", length = 50)
    private MusicImportance musicImportance;

    // Favorite decades (e.g., "1980s,1990s,2000s")
    @Column(name = "favorite_decades", length = 255)
    private String favoriteDecades;

    @Column(name = "open_to_new_genres", nullable = false)
    @Builder.Default
    private Boolean openToNewGenres = true;

    // When they listen to music most (e.g., "morning,commute,workout")
    @Column(name = "listening_times", length = 255)
    private String listeningTimes;

    // Average hours per day listening
    @Column(name = "hours_per_day")
    private Integer hoursPerDay;

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
}
