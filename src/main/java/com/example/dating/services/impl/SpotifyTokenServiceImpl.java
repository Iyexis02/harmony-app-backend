package com.example.dating.services.impl;

import com.example.dating.config.DistributedLockService;
import com.example.dating.exceptions.BadRequestException;
import com.example.dating.exceptions.SpotifyApiException;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.mappers.UserMapper;
import com.example.dating.models.auth.SpotifyTokenResponse;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.services.EncryptionService;
import com.example.dating.services.JwtService;
import com.example.dating.services.SpotifyTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Manages Spotify access token lifecycle: expiry check, refresh, and persistence.
 *
 * <p><b>Master Audit Batch A2 — Transaction boundary fix:</b>
 * {@link #getValidSpotifyToken} and {@link #refreshAndUpdateUserToken} are no longer
 * {@code @Transactional}. The Spotify HTTP call ({@code jwtService.refreshToken})
 * executes outside any transaction so no DB connection is held during the network
 * round-trip. Only the final token persistence ({@link #persistRefreshedToken}) runs
 * inside a short {@code @Transactional} — typically under 10 ms.
 *
 * <p>Before this fix, 10 concurrent token refreshes with a slow Spotify response
 * (10 s read timeout) would exhaust the default HikariCP pool (10 connections),
 * blocking all other DB operations across the entire application.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotifyTokenServiceImpl implements SpotifyTokenService {

    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final EncryptionService encryptionService;
    private final JwtService jwtService;
    private final DistributedLockService distributedLockService;

    /**
     * Return a valid (non-expired) Spotify access token for the user, refreshing if needed.
     *
     * <p>No {@code @Transactional} — the expiry check and decrypt are stateless.
     * If a refresh is needed, {@link #refreshAndUpdateUserToken} handles locking,
     * the HTTP call, and the DB write in separate phases.
     */
    @Override
    public String getValidSpotifyToken(User user) {
        if (user.getSpotifyId() == null) {
            throw new BadRequestException("User has not connected Spotify");
        }
        if (isTokenExpiredOrExpiring(user)) {
            log.info("Spotify token expired for user: {}, refreshing...", user.getSpotifyId());
            return refreshAndUpdateUserToken(user);
        }
        return encryptionService.decrypt(user.getSpotifyAccessToken());
    }

    /**
     * Refresh Spotify token and update user in database.
     *
     * <p>Acquires a distributed lock (Redis SETNX, 30 s TTL) so that at most one
     * instance refreshes the token for a given user at a time. Inside the lock the
     * user is re-read from the database: if another instance already completed a
     * refresh the token will no longer be expiring and the fresh token is returned
     * immediately without a second Spotify API call.
     *
     * <p>If the lock is held by another instance, this method waits briefly and
     * re-reads the user. If the token is then valid it is returned directly; otherwise
     * a {@link SpotifyApiException} is thrown so the caller can surface a 502.
     *
     * <p><b>Master Audit Batch A2:</b> No {@code @Transactional} on this method.
     * The Spotify HTTP call runs outside any transaction so no DB connection is
     * held during the network round-trip. Only the final
     * {@link #persistRefreshedToken} runs inside a short write transaction.
     *
     * <p>Throws SpotifyTokenRevokedException (401 — user must reconnect) or
     * SpotifyApiException (5xx / transient — caller should surface as 502) on failure.
     */
    @Override
    public String refreshAndUpdateUserToken(User user) {
        String lockKey = "lock:spotify:refresh:" + user.getId();

        String ownerId = distributedLockService.tryLock(lockKey, Duration.ofSeconds(30));
        if (ownerId == null) {
            // Another instance is refreshing — wait briefly then re-read.
            // No @Transactional on the caller, so Thread.sleep does not hold a DB connection.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            // Spring Data's findById runs in its own short auto-transaction.
            User refreshedUser = userJpaRepository.findById(user.getId())
                    .map(userMapper::toDomain)
                    .orElseThrow(() -> new UserNotFoundException("User not found"));
            if (!isTokenExpiredOrExpiring(refreshedUser)) {
                return encryptionService.decrypt(refreshedUser.getSpotifyAccessToken());
            }
            throw new SpotifyApiException(
                    "Spotify token refresh in progress on another instance; retry shortly");
        }

        try {
            // --- Phase 1 (no TX): Re-read user inside the lock ---
            // Spring Data findById uses its own short auto-transaction; connection released immediately.
            User freshUser = userJpaRepository.findById(user.getId())
                    .map(userMapper::toDomain)
                    .orElseThrow(() -> new UserNotFoundException("User not found"));

            if (!isTokenExpiredOrExpiring(freshUser)) {
                // Another instance already refreshed — return the valid token directly.
                return encryptionService.decrypt(freshUser.getSpotifyAccessToken());
            }

            String refreshToken = encryptionService.decrypt(freshUser.getSpotifyRefreshToken());

            // --- Phase 2 (no TX): HTTP call to Spotify ---
            // No DB connection held during this network round-trip (up to 33.5 s worst case).
            SpotifyTokenResponse tokenResponse = jwtService.refreshToken(refreshToken);

            // Prepare the new token values (encrypt before opening a transaction).
            String encryptedAccessToken = encryptionService.encrypt(tokenResponse.getAccess_token());
            Instant tokenExpires = Instant.now().plusSeconds(
                    Long.parseLong(tokenResponse.getExpires_in()));

            String encryptedRefreshToken = null;
            if (tokenResponse.getRefresh_token() != null &&
                    !tokenResponse.getRefresh_token().equals(refreshToken)) {
                encryptedRefreshToken = encryptionService.encrypt(tokenResponse.getRefresh_token());
            }

            // --- Phase 3 (@Transactional): Short DB write ---
            persistRefreshedToken(user.getId(), encryptedAccessToken, tokenExpires, encryptedRefreshToken);

            log.info("Successfully refreshed and updated Spotify token for user: {}",
                    freshUser.getSpotifyId());

            return tokenResponse.getAccess_token();
        } finally {
            distributedLockService.unlock(lockKey, ownerId);
        }
    }

    /**
     * Persist the refreshed Spotify token to the database in a short write transaction.
     *
     * <p>Loads the entity fresh (not from a stale domain object) so the {@code @Version}
     * field is current, then updates only the token-related fields.
     *
     * @param userId               the user's ID
     * @param encryptedAccessToken the new encrypted access token
     * @param tokenExpires         the new token expiry instant
     * @param encryptedRefreshToken the new encrypted refresh token, or null if unchanged
     */
    @Transactional
    public void persistRefreshedToken(String userId, String encryptedAccessToken,
                                      Instant tokenExpires, String encryptedRefreshToken) {
        UserEntity entity = userJpaRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        entity.setSpotifyAccessToken(encryptedAccessToken);
        entity.setSpotifyTokenExpires(tokenExpires);

        if (encryptedRefreshToken != null) {
            entity.setSpotifyRefreshToken(encryptedRefreshToken);
        }

        userJpaRepository.save(entity);
    }

    private boolean isTokenExpiredOrExpiring(User user) {
        if (user.getSpotifyTokenExpires() == null) {
            return true;
        }
        Instant expiryThreshold = Instant.now().plusSeconds(300);
        return user.getSpotifyTokenExpires().isBefore(expiryThreshold);
    }
}
