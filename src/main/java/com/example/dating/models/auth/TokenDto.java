package com.example.dating.models.auth;

import java.time.LocalDateTime;

public record TokenDto(String spotifyId, String email, String name, String spotifyAccessToken, String spotifyRefreshToken, LocalDateTime spotifyTokenExpires) {
}
