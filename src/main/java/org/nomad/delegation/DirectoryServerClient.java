package org.nomad.delegation;

import it.unimi.dsi.fastutil.objects.HashMap;
import it.unimi.dsi.fastutil.objects.ArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.nomad.delegation.models.GroupData;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.nomad.delegation.models.ZooDatatype;
import org.nomad.grpc.superpeerservice.VirtualPosition;

import java.io.IOException;

/* Interface to allow for abstraction around the Directory server implementation */
public interface DirectoryServerClient {

    void close();

    void init(double width, double height) throws Exception;

    String getGroupName();

    /**
     * Joins the group with the given {@link VirtualPosition} points within a virtual location
     *
     * @return GroupData group & ID, if a node was successfully assigned to a group
     * @throws Exception if a node failed to be assigned to a group
     */
    GroupData newGroupLeader(VirtualPosition position) throws Exception;

    /**
     * Joins the group with the given {@link VirtualPosition} points within a virtual location
     *
     * @return GroupData group & ID, if a node was successfully assigned to a group
     * @throws Exception if a node failed to be assigned to a group
     */
    GroupData joinGroup(VirtualPosition position) throws Exception;

    /**
     * @return List<String> Hostnames of all clients in the group (extracts from each group member)
     * @throws Exception if the Zookeeper commands fail
     */
    ArrayList<String> getGroupStorageHostnames(String group) throws Exception;

    ArrayList<String> getPeerHostnames(String group) throws Exception;

    /**
     * @return String Hostname of a client in the group
     * @throws Exception if the Zookeeper commands fail
     */
    String getDHTHostnamesFromGroupMember(String group) throws Exception;

    /**
     * Returns the String UUID of the leader node
     *
     * @return id
     * @throws Exception ZK errors, interruptions, etc
     */
    String getGroupLeader(String group) throws Exception;

    String getGroupLeaderId(String group) throws Exception;

    NeighbourData getNeighbouringLeaderData(VirtualPosition virtualPosition) throws Exception;

    VoronoiSitePoint getLeaderData(String group, String id) throws Exception;

    /**
     * Set the hostname for this host
     */
    void setGroupStorageHostname(String host) throws Exception;

    void setLeaderHostname(String host) throws Exception;

    void setLeaderVoronoiSitePoint(String host, double x, double y) throws Exception;

    void setPeerHostname(String host) throws Exception;

    void setDHTHostname(String host) throws Exception;

    String getGroupMemberDHTHostname(String groupName) throws Exception;

    HashMap<String, String> getDataMap();

    /**
     * MIGRATION
     * <p>
     * 1. Close the current group-member (Destroy znode) <p>
     * 2. Update the group name <p>
     * 3. Create new member <p>
     * 4. Create the new leader selector client <p>
     * <p>
     *
     * @param group to join
     * @throws Exception
     */
    void migrateMember(String group) throws Exception;

    ObjectOpenHashSet<String> getGroupMemberIDs();

    String extractDataForGroupMember(ZooDatatype dataKey, String id) throws IOException, ClassNotFoundException;

    boolean isWithinAOI(VirtualPosition virtualPosition);
}
