package com.example.dating.services;

import com.example.dating.models.auth.*;
import com.example.dating.models.user.common.dto.UserDtoRequest;

public interface AuthService {
    AuthResponseDto register(RegisterRequestDto request);
    AuthResponseDto login(LoginRequestDto request);
    AuthResponseDto spotifyLogin(UserDtoRequest request);
    void verifyEmail(String token);
    void forgotPassword(String email);
    void resetPassword(ResetPasswordRequestDto request);
    AuthResponseDto connectSpotify(String userId, ConnectSpotifyRequestDto request);
    void resendVerificationEmail(String email);
}
