package com.example.dating.models.user.tracks.dto;

import com.example.dating.models.user.artists.dao.SimplifiedArtistResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimplifiedTrackDto {

    private String id;
    private List<SimplifiedArtistResponse> artists;
    private Integer duration_ms;
    private String name;
}
