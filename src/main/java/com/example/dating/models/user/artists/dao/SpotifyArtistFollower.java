package com.example.dating.models.user.artists.dao;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpotifyArtistFollower {

    private String href;
    private Integer total;
}
