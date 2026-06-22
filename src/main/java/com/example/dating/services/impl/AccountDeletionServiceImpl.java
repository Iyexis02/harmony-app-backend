package com.example.dating.services.impl;

import com.example.dating.enums.user.AuthProvider;
import com.example.dating.exceptions.BadRequestException;
import com.example.dating.exceptions.InvalidCredentialsException;
import com.example.dating.exceptions.UserNotFoundException;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.repositories.MatchRepository;
import com.example.dating.repositories.UserBehavioralProfileRepository;
import com.example.dating.repositories.UserGenrePreferenceRepository;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserMatchScoreRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.AccountDeletionService;
import com.example.dating.services.matching.BehavioralScoreCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionServiceImpl implements AccountDeletionService {

    private final UserJpaRepository userJpaRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserSwipeRepository userSwipeRepository;
    private final MatchRepository matchRepository;
    private final UserMatchScoreRepository userMatchScoreRepository;
    private final UserGenrePreferenceRepository userGenrePreferenceRepository;
    private final UserBehavioralProfileRepository userBehavioralProfileRepository;
    private final BehavioralScoreCalculator behavioralScoreCalculator;

    @Override
    @Transactional
    public void deleteAccount(String userId, String password) {
        // Batch G — acquire PESSIMISTIC_WRITE lock on the user row so concurrent
        // swipe/match transactions that read this user will block until deletion commits.
        UserEntity userEntity = userJpaRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Email-authenticated users must confirm with their password before deletion.
        // Spotify-only users have no stored password; the JWT is sufficient proof of identity.
        // Invariant: AuthProvider is set once at creation (EMAIL on register, SPOTIFY on
        // Spotify-login) and never flips — there is no code path that adds a password to a
        // SPOTIFY user (resetPassword + connectSpotify are both gated on EMAIL). So Spotify
        // users genuinely have no password to verify here.
        if (userEntity.getAuthProvider() == AuthProvider.EMAIL) {
            if (password == null || password.isBlank()) {
                throw new BadRequestException("Password is required to delete an email-authenticated account");
            }
            if (!passwordEncoder.matches(password, userEntity.getPasswordHash())) {
                throw new InvalidCredentialsException("Incorrect password");
            }
        }

        // Master Batch E: Mark the user deleted and flush to the DB BEFORE cleaning up
        // child entities. A concurrent swipe transaction that loads this user and
        // checks isDeleted() will see the flag (once this TX commits) and reject the
        // insert. For the narrow window before this TX commits, the existing FK
        // constraints prevent orphaned rows by rolling back the swipe TX instead.
        userEntity.setDeleted(true);
        userJpaRepository.saveAndFlush(userEntity);

        // Invalidate in-memory behavioral score cache if a profile existed.
        // Uses ID-only projection to avoid loading the full entity into the session
        // (which would conflict with the subsequent bulk DELETE).
        userBehavioralProfileRepository.findProfileIdByUserId(userId)
                .ifPresent(behavioralScoreCalculator::invalidateCache);

        // Delete matching entities in FK-safe order.
        userSwipeRepository.deleteAllInvolvingUser(userId);
        matchRepository.deleteAllByUserId(userId);
        userMatchScoreRepository.deleteAllInvolvingUser(userId);
        userGenrePreferenceRepository.deleteByUserId(userId);
        userBehavioralProfileRepository.deleteByUserId(userId);

        // Delete user entity (cascades to photos, lifestyle, personality, etc.).
        userJpaRepository.deleteById(userId);

        log.info("Account deleted for userId: {}", userId);
    }
}
