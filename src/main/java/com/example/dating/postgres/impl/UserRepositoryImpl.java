package com.example.dating.postgres.impl;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.domain.User;
import com.example.dating.mappers.UserMapper;
import com.example.dating.postgres.UserRepository;
import com.example.dating.repositories.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;
    private final UserMapper mapper;

    @Override
    public User save(User user) throws IllegalArgumentException, OptimisticLockingFailureException {
        UserEntity entity = mapper.toEntity(user);
        UserEntity savedEntity = jpaRepository.save(entity);
        return mapper.toDomain(savedEntity);
    }

    @Override
    public Optional<User> findById(String id) {
        return jpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<User> findBySpotifyId(String spotifyId) {
        return jpaRepository.findBySpotifyId(spotifyId)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByEmailVerificationToken(String token) {
        return jpaRepository.findByEmailVerificationToken(token)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<User> findByPasswordResetToken(String token) {
        return jpaRepository.findByPasswordResetToken(token)
                .map(mapper::toDomain);
    }

    @Override
    public void deleteById(String id) {
        jpaRepository.deleteById(id);
    }
}