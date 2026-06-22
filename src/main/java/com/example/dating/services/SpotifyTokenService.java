package com.example.dating.services;

import com.example.dating.models.user.domain.User;

public interface SpotifyTokenService {
    String getValidSpotifyToken(User user);
    String refreshAndUpdateUserToken(User user);
}
