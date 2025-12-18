package com.example.dating.models.user.personality.dao;

import com.example.dating.enums.user.MBTI;
import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "user_personality")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPersonality {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(name = "bio", columnDefinition = "TEXT", length = 500)
    private String bio;

    // Comma-separated interests (e.g., "hiking,photography,cooking")
    @Column(name = "interests", columnDefinition = "TEXT")
    private String interests;

    @Enumerated(EnumType.STRING)
    @Column(name = "mbti", length = 10)
    private MBTI mbti;

    // What they're looking for in text form
    @Column(name = "looking_for_text", columnDefinition = "TEXT", length = 500)
    private String lookingForText;

    // Favorite quote or music lyric
    @Column(name = "favorite_quote", columnDefinition = "TEXT", length = 300)
    private String favoriteQuote;

    // Fun facts or conversation starters
    @Column(name = "conversation_starters", columnDefinition = "TEXT", length = 500)
    private String conversationStarters;

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
