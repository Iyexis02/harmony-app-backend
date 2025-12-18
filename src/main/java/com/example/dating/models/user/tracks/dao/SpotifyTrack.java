package com.example.dating.models.user.tracks.dao;

import com.example.dating.models.user.artists.dao.SimplifiedArtistResponse;
import com.example.dating.models.user.artists.dao.SpotifyExternalUrl;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpotifyTrack {


    private SpotifyAlbum album;
    private List<SimplifiedArtistResponse> artists;
    private List<String> available_markets;
    private Integer disc_number;
    private Integer duration_ms;
    private boolean explicit;
    private SpotifyExternalId external_ids;
    private SpotifyExternalUrl external_urls;
    private String href;
    private String id;
    private boolean is_playable;
    //private LinkedFrom linked_from
    private SpotifyTrackRestriction restrictions;
    private String name;
    private Integer popularity;
    private Integer track_number;
    private String type;
    private String uri;
    private boolean is_local;

}
