package com.example.dating.services.matching;

import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.repositories.UserSwipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a swipe within the caller's transaction.
 *
 * <p>Must be a separate Spring bean — {@code REQUIRED} propagation on a method
 * called from {@code this} inside the same bean would bypass the proxy and run
 * without a transaction. The separate bean ensures Spring's proxy intercepts the
 * call and correctly participates in the outer transaction.
 *
 * <p>With {@code REQUIRED} propagation the swipe INSERT shares the same transaction
 * as {@code SwipeService.recordSwipe()}.  This is required for Batch A: the advisory
 * pair lock acquired in {@code recordSwipe()} must hold until the INSERT commits, so
 * that a concurrent thread calling {@code hasUserLiked()} after the lock is released
 * always sees the first thread's committed swipe.
 */
@Component
@RequiredArgsConstructor
public class SwipePersistenceHelper {

    private final UserSwipeRepository swipeRepository;

    /**
     * Persists the swipe by joining the caller's transaction ({@code REQUIRED}).
     *
     * <p>A duplicate {@code uk_swiper_swiped} constraint violation surfaces as a
     * {@code DataIntegrityViolationException} at flush time. Because this runs inside
     * the outer transaction, the caller must catch that exception and handle it
     * (e.g., rethrow as {@code DuplicateSwipeException}) before the transaction commits.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UserSwipe saveSwipe(UserSwipe swipe) {
        return swipeRepository.save(swipe);
    }
}
