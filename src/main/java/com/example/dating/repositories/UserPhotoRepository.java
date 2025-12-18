package com.example.dating.repositories;

import com.example.dating.models.user.photos.dao.UserPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPhotoRepository extends JpaRepository<UserPhoto, String> {
    List<UserPhoto> findByUserIdOrderByDisplayOrderAsc(String userId);
    Optional<UserPhoto> findByUserIdAndIsPrimaryTrue(String userId);
    void deleteByUserId(String userId);
}
