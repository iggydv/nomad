package org.nomad.delegation.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NeighbourData {
    String groupName;
    VoronoiSitePoint leaderData;

    public static NeighbourData defaultInstance() {
        return NeighbourData.builder().groupName("").leaderData(VoronoiSitePoint.builder().build()).build();
    }
}
