package com.example.dating.models.user.domain;

import com.example.dating.models.user.artists.dao.SpotifyImage;
import com.example.dating.models.user.artists.dao.SpotifyExternalUrl;
import com.example.dating.models.user.artists.dao.SpotifyArtistFollower;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpotifyArtist {

    private SpotifyExternalUrl external_urls;
    private SpotifyArtistFollower followers;
    private List<String> genres;
    private String href;
    private String id;
    private List<SpotifyImage> images;
    private String name;
    private Integer popularity;
    private String type;
    private String uri;

}
