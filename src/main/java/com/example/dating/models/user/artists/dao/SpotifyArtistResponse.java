package com.example.dating.models.user.artists.dao;

import com.example.dating.models.user.domain.SpotifyArtist;
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
public class SpotifyArtistResponse extends SpotifyTopItems {

    private List<SpotifyArtist> items;

}
