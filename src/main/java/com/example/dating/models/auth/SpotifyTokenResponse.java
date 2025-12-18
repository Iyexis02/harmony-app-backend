package com.example.dating.models.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class SpotifyTokenResponse {

    private String access_token;
    private String refresh_token;
    private String expires_in;
}
