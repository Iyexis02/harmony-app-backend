package com.example.dating.controllers;

import com.example.dating.mappers.UserMapper;
import com.example.dating.models.auth.DeleteAccountRequestDto;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dto.UserProfileResponseDto;
import com.example.dating.repositories.UserJpaRepository;
import com.example.dating.repositories.UserSwipeRepository;
import com.example.dating.services.AccountDeletionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Account", description = "Account management: view profile and delete account")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AccountController {

    private final AccountDeletionService accountDeletionService;
    private final UserJpaRepository userJpaRepository;
    private final UserMapper userMapper;
    private final UserSwipeRepository userSwipeRepository;

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(
            @Valid @RequestBody(required = false) DeleteAccountRequestDto request,
            Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String userId = jwt.getClaimAsString("userId");
        String password = request != null ? request.getPassword() : null;
        accountDeletionService.deleteAccount(userId, password);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<UserProfileResponseDto> getUserProfile(
            @PathVariable String id,
            Authentication authentication) {

        UserEntity user = userJpaRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Determine ownership — owners bypass all privacy gates
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String requestingUserId = jwt.getClaimAsString("userId");
        boolean isOwner = id.equals(requestingUserId);

        if (!isOwner) {
            // Block-relationship gate: a user must not see the profile of someone they
            // have blocked or who has blocked them. Return 404 (not 403) so the status
            // code does not confirm whether the target user exists or whether a block
            // relationship is in place. Mirrors the privacy-gate enumeration defence below.
            if (userSwipeRepository.existsBlockBetween(requestingUserId, id)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            // Privacy gate — return 404 (not 403) to prevent user-ID enumeration.
            var privacy = user.getPrivacySettings();
            if (privacy != null && Boolean.FALSE.equals(privacy.getIsProfilePublic())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        }

        return ResponseEntity.ok(userMapper.toUserProfileResponse(user, isOwner));
    }
}
