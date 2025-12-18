package com.example.dating.models.user.tracks.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpotifyExternalId {

    private String isrc;
    private String ean;
    private String upc;
}
