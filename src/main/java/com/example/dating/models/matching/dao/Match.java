package com.example.dating.models.matching.dao;

import com.example.dating.enums.matching.MatchSource;
import com.example.dating.enums.matching.MatchStatus;
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

/**
 * Represents a mutual match between two users (both swiped right).
 * This is the core entity for actual connections between users.
 */
@Entity
@Getter
@Setter
@ToString
@Table(name = "matches",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_match_user_pair", columnNames = {"user_a_id", "user_b_id"})
       },
       indexes = {
           @Index(name = "idx_match_user_a", columnList = "user_a_id"),
           @Index(name = "idx_match_user_b", columnList = "user_b_id"),
           @Index(name = "idx_match_status", columnList = "status"),
           @Index(name = "idx_match_created", columnList = "matched_at"),
           // Batch C: findRecentMatches() filters on status + matched_at range;
           // composite avoids a heap scan after the status index narrows the set.
           @Index(name = "idx_status_matched_at", columnList = "status,matched_at"),
           // Scalability Batch B: OR-clause queries always filter (user_a_id|user_b_id) AND status.
           // Without composites, Postgres does a bitmap union on two single-col indexes then
           // re-checks status as a heap filter — one composite per side eliminates that.
           @Index(name = "idx_match_a_status", columnList = "user_a_id, status"),
           @Index(name = "idx_match_b_status", columnList = "user_b_id, status"),
           @Index(name = "idx_match_a_status_conv", columnList = "user_a_id, status, conversation_started"),
           @Index(name = "idx_match_b_status_conv", columnList = "user_b_id, status, conversation_started")
       })
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Long version = 0L;

    /**
     * First user in the match.
     * Convention: user_a_id < user_b_id (alphabetically) to ensure uniqueness.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_a_id", nullable = false)
    private UserEntity userA;

    /**
     * Second user in the match.
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_b_id", nullable = false)
    private UserEntity userB;

    /**
     * When the mutual match occurred.
     */
    @Column(name = "matched_at", nullable = false)
    @Builder.Default
    private LocalDateTime matchedAt = LocalDateTime.now();

    /**
     * Match quality score from userA's perspective (A→B).
     * Useful for analytics: do high-scoring matches lead to more conversations?
     */
    @Column(name = "match_score")
    private Double matchScore;

    /**
     * Match quality score from userB's perspective (B→A).
     * Stored separately because scoring is directional (musicMatchImportance differs per user).
     * Null for matches created before Integrity Batch H.
     */
    @Column(name = "match_score_b")
    private Double matchScoreB;

    /**
     * Match status.
     * Stored as a lowercase string via {@link MatchStatusConverter} (e.g. {@code "active"}).
     */
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private MatchStatus status = MatchStatus.ACTIVE;

    /**
     * Has a conversation been started?
     * Tracks if at least one message has been sent.
     */
    @Column(name = "conversation_started", nullable = false)
    @Builder.Default
    private Boolean conversationStarted = false;

    /**
     * Timestamp of the first message sent.
     */
    @Column(name = "first_message_at")
    private LocalDateTime firstMessageAt;

    /**
     * Timestamp of the most recent message in this match.
     */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /**
     * Total number of messages exchanged.
     */
    @Column(name = "message_count")
    @Builder.Default
    private Integer messageCount = 0;

    /**
     * Source of the match.
     * Stored as a lowercase underscore string via {@link MatchSourceConverter}
     * (e.g. {@code "mutual_swipe"}).
     */
    @Column(name = "match_source", length = 50)
    @Builder.Default
    private MatchSource matchSource = MatchSource.MUTUAL_SWIPE;

    /**
     * When user A or B unmatched (if status changed from active).
     */
    @Column(name = "unmatched_at")
    private LocalDateTime unmatchedAt;

    /**
     * Who initiated the unmatch (user_a or user_b).
     */
    @Column(name = "unmatched_by", length = 36)
    private String unmatchedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Batch G: enforce the (user_a_id < user_b_id) database convention at the
    // JPA layer so that any save() call — not just createMatch() — is guarded.
    // Note: insertMatchIfAbsent() is a native query and bypasses @PrePersist;
    // createMatch() already handles ordering before calling that query.
    @PrePersist
    private void validateUserOrderingOnPersist() {
        if (userA != null && userB != null
                && userA.getId().compareTo(userB.getId()) > 0) {
            throw new IllegalStateException(
                    "Match invariant violated: userA.id must be <= userB.id "
                    + "(got userA=" + userA.getId() + ", userB=" + userB.getId() + ")");
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (userA != null && userB != null
                && userA.getId().compareTo(userB.getId()) > 0) {
            throw new IllegalStateException(
                    "Match invariant violated: userA.id must be <= userB.id "
                    + "(got userA=" + userA.getId() + ", userB=" + userB.getId() + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Match other)) return false;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? Objects.hashCode(id) : getClass().hashCode();
    }
}
