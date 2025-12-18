package com.example.dating.models.user.tracks.dao;

import com.example.dating.models.user.common.dao.SpotifyTopItems;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Data
public class SpotifyTracksResponse extends SpotifyTopItems {

    private List<SpotifyTrack> items;


}
