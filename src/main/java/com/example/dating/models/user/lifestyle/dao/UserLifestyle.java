package com.example.dating.models.user.lifestyle.dao;

import com.example.dating.enums.user.EducationLevel;
import com.example.dating.enums.user.KidsPreference;
import com.example.dating.enums.user.PoliticalViews;
import com.example.dating.enums.user.RelationshipStatus;
import com.example.dating.enums.user.Religion;
import com.example.dating.enums.user.SmokingHabits;
import com.example.dating.enums.user.DrinkingHabits;
import com.example.dating.enums.user.ExerciseFrequency;
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
@Table(name = "user_lifestyle", indexes = {
    @Index(name = "idx_lifestyle_user", columnList = "user_id")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLifestyle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ToString.Exclude
    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(name = "education", length = 100)
    private EducationLevel education;

    @Column(name = "occupation", length = 150)
    private String occupation;

    @Column(name = "company", length = 150)
    private String company;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_status", length = 50)
    private RelationshipStatus relationshipStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "wants_kids", length = 50)
    private KidsPreference wantsKids;

    @Enumerated(EnumType.STRING)
    @Column(name = "smoking_habits", length = 50)
    private SmokingHabits smokingHabits;

    @Enumerated(EnumType.STRING)
    @Column(name = "drinking_habits", length = 50)
    private DrinkingHabits drinkingHabits;

    @Enumerated(EnumType.STRING)
    @Column(name = "exercise_frequency", length = 50)
    private ExerciseFrequency exerciseFrequency;

    @Enumerated(EnumType.STRING)
    @Column(name = "religion", length = 50)
    private Religion religion;

    @Enumerated(EnumType.STRING)
    @Column(name = "political_views", length = 50)
    private PoliticalViews politicalViews;

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
        if (!(o instanceof UserLifestyle other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
