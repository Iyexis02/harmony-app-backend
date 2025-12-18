package com.example.dating.models.user.artists.dto;

import com.example.dating.models.user.artists.dao.SpotifyImage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimplifiedArtistDto {

    private String id;
    List<String> genres;
    List<SpotifyImage> images;
    String name;
}
