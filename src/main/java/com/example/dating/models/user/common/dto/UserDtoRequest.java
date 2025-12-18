package com.example.dating.models.user.common.dto;

import lombok.Builder;


@Builder
public record UserDtoRequest(
        String spotifyId,
        String email,
        String name,
        String spotifyAccessToken,
        String spotifyRefreshToken,
        Long spotifyTokenExpiresAt,
        String imageUrl
) {}