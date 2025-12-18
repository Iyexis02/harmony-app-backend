package com.example.dating.models.user.tracks.dao;

import com.example.dating.models.user.artists.dao.SimplifiedArtistResponse;
import com.example.dating.models.user.artists.dao.SpotifyExternalUrl;
import com.example.dating.models.user.artists.dao.SpotifyImage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpotifyAlbum {

    private String album_type;
    private Integer total_tracks;
    private List<String> available_markets;
    private SpotifyExternalUrl external_urls;
    private String href;
    private String id;
    private List<SpotifyImage> images;
    private String name;
    private String release_date;
    private AlbumRestriction restrictions;
    private String type;
    private String uri;
    private List<SimplifiedArtistResponse> artists;
    private boolean is_playable;
    private String release_date_precision;

}
