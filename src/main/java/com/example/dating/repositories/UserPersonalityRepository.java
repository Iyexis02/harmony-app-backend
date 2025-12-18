package com.example.dating.repositories;

import com.example.dating.models.user.personality.dao.UserPersonality;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPersonalityRepository extends JpaRepository<UserPersonality, String> {
    Optional<UserPersonality> findByUserId(String userId);
}
