package com.example.dating.postgres;

import com.example.dating.models.user.domain.User;

import java.util.Optional;

public interface UserRepository {
    User save(User user);
    Optional<User> findById(String id);
    Optional<User> findByEmail(String email);
    Optional<User> findBySpotifyId(String spotifyId);
    Optional<User> findByEmailVerificationToken(String token);
    Optional<User> findByPasswordResetToken(String token);
    void deleteById(String id);
}