package org.nomad.delegation;

import it.unimi.dsi.fastutil.objects.HashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import it.unimi.dsi.fastutil.objects.ArrayLists;
import org.kynosarges.tektosyne.geometry.PointD;
import org.kynosarges.tektosyne.geometry.RectD;
import org.kynosarges.tektosyne.geometry.Voronoi;
import org.kynosarges.tektosyne.geometry.VoronoiResults;
import org.kynosarges.tektosyne.subdivision.Subdivision;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

public class VoronoiWrapper {
    private final Logger logger = LoggerFactory.getLogger(VoronoiWrapper.class);
    private final HashMap<PointD, String> superPeerLocationsWithIds = new HashMap<>(); // Point - Hostname
    private Subdivision voronoiSubdivision;
    private RectD mapLimits;

    public VoronoiWrapper(double height, double width) {
        mapLimits = new RectD(0, 0, width, height);
    }

    public boolean voronoiMapGenerated() {
        return voronoiSubdivision != null;
    }

    public String findVoronoiGroupToJoin(VirtualPosition point) throws IllegalStateException {
        PointD pointD = new PointD(point.getX(), point.getY());
        if (voronoiSubdivision != null) {
            PointD site = voronoiSubdivision.findNearestNode(pointD);
            return superPeerLocationsWithIds.get(site);
        } else {
            throw new IllegalStateException("Voronoi map is not constructed, you need at-least 2 SPs for this.");
        }
    }

    public VirtualPosition findVoronoiGroupPointToJoin(VirtualPosition point) {
        PointD pointD = new PointD(point.getX(), point.getY());
        if (voronoiSubdivision != null) {
            PointD nearestNode = voronoiSubdivision.findNearestNode(pointD);
            return VirtualPosition.newBuilder().setX((int) nearestNode.x).setY((int) nearestNode.y).build();
        } else {
            throw new IllegalStateException("Voronoi map is not constructed, you need at-least 2 SPs for this.");
        }
    }

    public void updateVoronoiMap(VoronoiSitePoint point) {
        PointD newSPPoint = new PointD(point.getX(), point.getY());
        superPeerLocationsWithIds.put(newSPPoint, point.getHostname());
        generateVoronoiMap();
    }

    /**
     * Removes peer from internal map and updates voronoi map
     *
     * @param point
     */
    public void removeSuperPeerLocation(VoronoiSitePoint point) {
        PointD newSPPoint = new PointD(point.getX(), point.getY());
        superPeerLocationsWithIds.remove(newSPPoint, point.getHostname());
        generateVoronoiMap();
    }

    public void updateMapLimits(int height, int width) {
        mapLimits = new RectD(0, 0, width, height);
    }

    /**
     * Add site-point to {@link Subdivision} by adding to list map coordinates for the Voronoi diagram
     *
     * @param points
     */
    public void updateVoronoiMap(ArrayList<VoronoiSitePoint> points) {
        points.forEach(voronoiSitePoint -> {
            logger.info("Adding new point to map: {}", voronoiSitePoint);
            PointD newSPPoint = new PointD(voronoiSitePoint.getX(), voronoiSitePoint.getY());
            superPeerLocationsWithIds.putIfAbsent(newSPPoint, voronoiSitePoint.getHostname());
        });
        generateVoronoiMap();
    }

    public int getRegionsCount() {
        return superPeerLocationsWithIds.size();
    }

    /**
     * Return a list of neighbouring peer Ids if any exist
     *
     * @param point A Super-peer location (site-point)
     * @return List of Neighbour Id's if the point is a site-point, null otherwise
     */
    public ArrayList<String> getNeighbours(VirtualPosition point) {
        PointD pointD = new PointD(point.getX(), point.getY());
        try {
            ArrayList<PointD> neighbours = new ObjectArrayList<>(voronoiSubdivision.getNeighbors(pointD));
            return new ObjectArrayList<>(neighbours.stream().map(pointD1 -> superPeerLocationsWithIds.get(pointD1)).collect(Collectors.toList()));
        } catch (NullPointerException e) {
            return ArrayLists.emptyList();
        }
    }

    /**
     * Populates the voronoi {@link Subdivision}, which is used to determine which group a point falls in
     */
    private void generateVoronoiMap() {
        PointD[] sitePoints = superPeerLocationsWithIds.keySet().toArray(new PointD[0]);
        if (sitePoints.length <= 1) {
            logger.warn("More site-points (>= 2) required to generate voronoi map");
            voronoiSubdivision = null;
        } else {
            VoronoiResults voronoi = Voronoi.findAll(sitePoints, mapLimits);
            voronoiSubdivision = voronoi.toDelaunaySubdivision(false);
        }
    }
}
