package org.nomad.delegation.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VoronoiSitePoint {
    String hostname;
    /**
     * x, y coordinates of the Super-peer that needs to be added to the voronoi map
     *
     * @apiNote z position is ignored
     */
    double x;
    double y;
}
