package com.example.dating.models.auth;

import lombok.Data;

@Data
public class DeleteAccountRequestDto {

    /**
     * Required for EMAIL-authenticated accounts — verified against the stored password hash.
     * Spotify-only accounts may omit this field (the JWT is sufficient proof of identity).
     */
    private String password;
}
