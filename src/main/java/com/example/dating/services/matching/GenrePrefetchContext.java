package com.example.dating.services.matching;

import com.example.dating.models.matching.dao.UserGenrePreference;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-local cache of pre-loaded genre preferences for a single scoring pass.
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Set once in {@link MatchRecommendationService#findPotentialMatches} before the
 *       scoring loop, using prefs batch-loaded for all candidates and the current user.</li>
 *   <li>Read by {@link MatchScoreCalculator} and {@link BehavioralScoreCalculator} instead
 *       of issuing per-candidate queries.  Reduces ~1 500 per-candidate SELECTs to one
 *       batch query per feed request.</li>
 *   <li>Always cleared in a {@code finally} block — this is mandatory to avoid
 *       contaminating pooled threads that handle subsequent requests.</li>
 * </ol>
 *
 * <p><b>Thread-safety:</b> each HTTP request is handled on a single thread.  The
 * {@link ThreadLocal} guarantees no state is shared across concurrent requests.
 */
@Component
public class GenrePrefetchContext {

    private final ThreadLocal<Map<String, List<UserGenrePreference>>> holder = new ThreadLocal<>();

    /**
     * Load the pre-fetched preference map for this thread's scoring pass.
     * <b>Must</b> be followed by a {@link #clear()} call in a {@code finally} block.
     *
     * @param prefsByUserId map of userId → ordered list of genre preferences
     */
    public void set(Map<String, List<UserGenrePreference>> prefsByUserId) {
        holder.set(prefsByUserId);
    }

    /**
     * Return the pre-loaded preferences for {@code userId}.
     *
     * <ul>
     *   <li>{@code Optional.empty()} — no prefetch is active; the caller must fall back
     *       to a direct repository query.</li>
     *   <li>{@code Optional.of(emptyList)} — prefetch was active and this user has no
     *       genre preferences; the caller should treat this as an empty result (no DB
     *       round-trip needed).</li>
     *   <li>{@code Optional.of(prefs)} — use these prefs directly.</li>
     * </ul>
     */
    public Optional<List<UserGenrePreference>> find(String userId) {
        Map<String, List<UserGenrePreference>> map = holder.get();
        if (map == null) {
            return Optional.empty(); // prefetch not active — signal to fall back to DB
        }
        return Optional.of(map.getOrDefault(userId, Collections.emptyList()));
    }

    /**
     * Returns {@code true} if a prefetch map is loaded on this thread.
     * Useful for tests and defensive checks.
     */
    public boolean isActive() {
        return holder.get() != null;
    }

    /**
     * Remove the prefetch map from this thread.
     * <b>Always call this in a {@code finally} block</b> to prevent stale context
     * surviving thread-pool reuse.
     */
    public void clear() {
        holder.remove();
    }
}
