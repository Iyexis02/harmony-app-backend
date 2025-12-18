package com.example.dating.models.user.dating.dao;

import com.example.dating.enums.user.Gender;
import com.example.dating.enums.user.RelationshipGoal;
import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "user_dating_preferences")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDatingPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    // Age range preferences
    @Column(name = "min_age")
    private Integer minAge;

    @Column(name = "max_age")
    private Integer maxAge;

    // Distance preference in kilometers
    @Column(name = "max_distance_km")
    private Integer maxDistanceKm;

    // Interested in these genders (comma-separated: "MALE,FEMALE")
    @Column(name = "interested_in_genders", length = 255)
    private String interestedInGenders;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_goal", length = 50)
    private RelationshipGoal relationshipGoal;

    // Deal breakers (comma-separated)
    @Column(name = "deal_breakers", columnDefinition = "TEXT")
    private String dealBreakers;

    // Show me option (everyone, popular profiles, etc.)
    @Column(name = "show_me", length = 50)
    @Builder.Default
    private String showMe = "EVERYONE";

    // Music taste compatibility weight (0-100, how important is music match)
    @Column(name = "music_match_importance")
    @Builder.Default
    private Integer musicMatchImportance = 70;

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
