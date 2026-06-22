package com.example.dating.models.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PotentialMatchPage {

    private List<PotentialMatch> matches;
    private int total;
    private int limit;
    private int offset;
    private boolean hasMore;
}
