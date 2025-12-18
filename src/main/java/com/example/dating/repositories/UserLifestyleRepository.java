package com.example.dating.repositories;

import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserLifestyleRepository extends JpaRepository<UserLifestyle, String> {
    Optional<UserLifestyle> findByUserId(String userId);
}
