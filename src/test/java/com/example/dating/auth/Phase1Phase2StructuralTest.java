package com.example.dating.auth;

import com.example.dating.controllers.AccountController;
import com.example.dating.enums.user.AuthProvider;
import com.example.dating.exceptions.BadRequestException;
import com.example.dating.exceptions.GlobalExceptionHandler;
import com.example.dating.repositories.UserSwipeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural regression guards for the Phase 1 + 2 (Authorization + Error Handling)
 * implementation. Pure JUnit — no Spring context, no DB.
 *
 * <p>These tests fail fast if a future change reverts a critical contract:
 * <ul>
 *   <li>{@code BadRequestException} exists and extends {@code RuntimeException}.</li>
 *   <li>{@code GlobalExceptionHandler} has a handler method for it.</li>
 *   <li>{@code UserSwipeRepository.existsBlockBetween(a, b)} exists — it is the
 *       single SELECT EXISTS that replaced the unbounded list-and-contains pattern
 *       in the score and profile-read paths.</li>
 *   <li>{@code AccountController} depends on {@code UserSwipeRepository} — proves
 *       the block-relationship gate is wired in.</li>
 *   <li>{@code AuthProvider} only contains {@code SPOTIFY} and {@code EMAIL} —
 *       the invariant that {@code AccountDeletionService} relies on for its
 *       Spotify-only delete path. If this enum ever gains a {@code BOTH} or
 *       similar value, that service needs to revisit its re-auth gate.</li>
 * </ul>
 */
class Phase1Phase2StructuralTest {

    @Test
    @DisplayName("BadRequestException is a RuntimeException with a single-arg String constructor")
    void badRequestException_shapeContract() throws Exception {
        assertThat(RuntimeException.class).isAssignableFrom(BadRequestException.class);
        assertThat(BadRequestException.class.getDeclaredConstructor(String.class)).isNotNull();
        assertThat(new BadRequestException("hello").getMessage()).isEqualTo("hello");
    }

    @Test
    @DisplayName("GlobalExceptionHandler has an @ExceptionHandler for BadRequestException")
    void globalExceptionHandler_handlesBadRequestException() {
        boolean found = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .anyMatch(m -> Arrays.asList(m.getAnnotation(ExceptionHandler.class).value())
                        .contains(BadRequestException.class));
        assertThat(found)
                .as("GlobalExceptionHandler must declare @ExceptionHandler(BadRequestException.class)")
                .isTrue();
    }

    @Test
    @DisplayName("UserSwipeRepository.existsBlockBetween(a, b) exists with the expected signature")
    void userSwipeRepository_existsBlockBetweenContract() throws NoSuchMethodException {
        Method m = UserSwipeRepository.class.getMethod("existsBlockBetween", String.class, String.class);
        assertThat(m.getReturnType()).isEqualTo(boolean.class);
    }

    @Test
    @DisplayName("AccountController depends on UserSwipeRepository (block-relationship gate is wired)")
    void accountController_dependsOnUserSwipeRepository() {
        boolean dependsOnSwipeRepo = Arrays.stream(AccountController.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(UserSwipeRepository.class::equals);
        assertThat(dependsOnSwipeRepo)
                .as("AccountController must inject UserSwipeRepository for the block-relationship gate on /me/profile")
                .isTrue();
    }

    @Test
    @DisplayName("AuthProvider contains exactly { SPOTIFY, EMAIL } — invariant relied on by AccountDeletionService")
    void authProvider_invariantHolds() {
        Set<AuthProvider> values = Set.of(AuthProvider.values());
        assertThat(values).containsExactlyInAnyOrder(AuthProvider.SPOTIFY, AuthProvider.EMAIL);
    }
}
