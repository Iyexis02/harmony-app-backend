package com.example.dating.models.user.artists.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimplifiedArtistResponse {

    private SpotifyExternalUrl external_urls;
    private String href;
    private String id;
    private String name;
    private String type;
    private String uri;
}
