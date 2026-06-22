package com.example.dating.matching;

import com.example.dating.exceptions.SwipeNotFoundException;
import com.example.dating.exceptions.SwipeUndoNotAllowedException;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.matching.dao.Match;
import com.example.dating.models.matching.dao.UserSwipe;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.matching.MatchRecommendationService;
import com.example.dating.services.matching.MatchService;
import com.example.dating.services.matching.SwipePersistenceHelper;
import com.example.dating.services.matching.SwipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Undo-swipe behavior — DELETE /api/v1/matching/swipe/{swipedUserId}.
 *
 * <p>Before the fix this endpoint did not exist, so the call fell through to a
 * {@code NoResourceFoundException} that the {@code @ExceptionHandler(Exception.class)} catch-all
 * turned into a 500. It now removes the single swipe row for (swiper → swiped) so the candidate
 * resurfaces, with a 409 guard for swipes that already formed a match and a 404 when there is
 * nothing to undo.
 *
 * <p>Tests:
 * <ol>
 *   <li>Existing non-match swipe → deleted; pair lock acquired before the read.</li>
 *   <li>No swipe → {@link SwipeNotFoundException} (404), nothing deleted.</li>
 *   <li>Swipe with {@code resultedInMatch=true} → {@link SwipeUndoNotAllowedException} (409).</li>
 *   <li>Swipe linked to a {@link Match} → {@link SwipeUndoNotAllowedException} (409).</li>
 *   <li>Block swipe (no match) → deleted (no special-case; acts as unblock).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SwipeUndoTest {

    @Mock private UserSwipeRepository swipeRepository;
    @Mock private UserJpaRepository userRepository;
    @Mock private MatchService matchService;
    @Mock private SwipePersistenceHelper swipePersistenceHelper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MatchRecommendationService recommendationService;
    @Mock private UserMapper userMapper;

    private SwipeService swipeService;

    private static final String SWIPER_ID = "swiper-1";
    private static final String SWIPED_ID = "swiped-2";

    @BeforeEach
    void setUp() {
        swipeService = new SwipeService(
                swipeRepository, userRepository, matchService, swipePersistenceHelper,
                eventPublisher, recommendationService, userMapper);
    }

    private User swiper() {
        return User.builder().id(SWIPER_ID).build();
    }

    private UserSwipe swipe(String action, boolean resultedInMatch, Match match) {
        UserSwipe s = UserSwipe.builder()
                .action(action)
                .resultedInMatch(resultedInMatch)
                .match(match)
                .build();
        s.setId("swipe-id");
        return s;
    }

    @Test
    @DisplayName("Existing non-match swipe is deleted; pair lock acquired before the read")
    void undo_existingSwipe_deleted() {
        UserSwipe existing = swipe("like", false, null);
        when(swipeRepository.findByUserIds(SWIPER_ID, SWIPED_ID)).thenReturn(Optional.of(existing));

        swipeService.undoSwipe(swiper(), SWIPED_ID);

        // Lock must be taken before we read the row we are about to delete.
        var order = inOrder(swipeRepository);
        order.verify(swipeRepository).acquirePairLock(any());
        order.verify(swipeRepository).findByUserIds(SWIPER_ID, SWIPED_ID);
        order.verify(swipeRepository).delete(existing);
    }

    @Test
    @DisplayName("No swipe to undo → SwipeNotFoundException (404), nothing deleted")
    void undo_noSwipe_throwsNotFound() {
        when(swipeRepository.findByUserIds(SWIPER_ID, SWIPED_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> swipeService.undoSwipe(swiper(), SWIPED_ID))
                .isInstanceOf(SwipeNotFoundException.class);

        verify(swipeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Swipe with resultedInMatch=true → SwipeUndoNotAllowedException (409), not deleted")
    void undo_matchedFlag_throwsConflict() {
        when(swipeRepository.findByUserIds(SWIPER_ID, SWIPED_ID))
                .thenReturn(Optional.of(swipe("like", true, null)));

        assertThatThrownBy(() -> swipeService.undoSwipe(swiper(), SWIPED_ID))
                .isInstanceOf(SwipeUndoNotAllowedException.class);

        verify(swipeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Swipe linked to a Match → SwipeUndoNotAllowedException (409), not deleted")
    void undo_linkedMatch_throwsConflict() {
        when(swipeRepository.findByUserIds(SWIPER_ID, SWIPED_ID))
                .thenReturn(Optional.of(swipe("like", false, new Match())));

        assertThatThrownBy(() -> swipeService.undoSwipe(swiper(), SWIPED_ID))
                .isInstanceOf(SwipeUndoNotAllowedException.class);

        verify(swipeRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Block swipe with no match is deleted (no special-case — acts as unblock)")
    void undo_blockSwipe_deleted() {
        UserSwipe block = swipe("block", false, null);
        when(swipeRepository.findByUserIds(SWIPER_ID, SWIPED_ID)).thenReturn(Optional.of(block));

        swipeService.undoSwipe(swiper(), SWIPED_ID);

        verify(swipeRepository).delete(block);
    }
}
