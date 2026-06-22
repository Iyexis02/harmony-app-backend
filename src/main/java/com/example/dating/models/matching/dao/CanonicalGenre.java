package com.example.dating.models.matching.dao;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Master list of normalized music genres.
 * Used to ensure consistency between Spotify-derived genres and manually selected genres.
 * Supports hierarchical relationships (parent-child genres).
 */
@Entity
@Getter
@Setter
@ToString
@Table(name = "canonical_genres", indexes = {
        @Index(name = "idx_genre_name", columnList = "name"),
        @Index(name = "idx_genre_parent", columnList = "parent_genre_id")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanonicalGenre {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    /**
     * Canonical genre name (lowercase, hyphenated).
     * Example: "indie-rock", "electronic", "hip-hop"
     */
    @Column(name = "name", unique = true, nullable = false, length = 100)
    private String name;

    /**
     * Display-friendly name for UI.
     * Example: "Indie Rock", "Electronic", "Hip Hop"
     */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /**
     * Parent genre for hierarchical relationships.
     * Example: "indie-rock" has parent "rock"
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_genre_id")
    private CanonicalGenre parentGenre;

    /**
     * Comma-separated list of Spotify genre names that map to this canonical genre.
     * Example: "indie rock,indie_rock,alternative rock,alt-rock"
     * Used for fuzzy matching when importing from Spotify.
     */
    @Column(name = "spotify_aliases", columnDefinition = "TEXT")
    private String spotifyAliases;

    /**
     * Description of this genre.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Indicates if this is a primary/popular genre (shown in UI) vs derived/niche genre.
     */
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    /**
     * Display order for UI sorting (lower = shown first).
     */
    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CanonicalGenre other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
