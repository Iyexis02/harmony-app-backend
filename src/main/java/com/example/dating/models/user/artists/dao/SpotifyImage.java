package com.example.dating.models.user.artists.dao;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpotifyImage {
    private String url;
    private Integer height;
    private Integer width;
}
