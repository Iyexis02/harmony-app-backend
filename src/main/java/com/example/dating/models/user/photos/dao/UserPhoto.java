package com.example.dating.models.user.photos.dao;

import com.example.dating.models.user.common.dao.UserEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "user_photos", indexes = {
        @Index(name = "idx_user_photo", columnList = "user_id,display_order")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "image_url", nullable = false, length = 1024, columnDefinition = "TEXT")
    private String imageUrl;

    // Order in which photos are displayed (0 = primary photo)
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    // Optional caption for the photo
    @Column(name = "caption", length = 255)
    private String caption;

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
