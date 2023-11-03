package org.nomad.delegation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.NonNull;
import org.apache.curator.RetryLoop;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.recipes.nodes.GroupMember;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.async.AsyncCuratorFramework;
import org.apache.curator.x.async.WatchMode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.delegation.models.DhtNodeData;
import org.nomad.delegation.models.GroupData;
import org.nomad.delegation.models.LeaderNodeData;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.nomad.delegation.models.ZooDatatype;
import org.nomad.delegation.parsers.DhtNodeDataParser;
import org.nomad.delegation.parsers.LeaderNodeDataParser;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.nomad.delegation.parsers.LeaderNodeDataParser.parseLeaderNodeData;

@Service("ZookeeperDirectoryServerClient")
public class ZookeeperDirectoryServerClient implements DirectoryServerClient {
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperDirectoryServerClient.class);

    protected final String zookeeperIp;

    private final String BASE_PATH = "/GroupStorage";
    private final String INLINE_BASE_PATH = "/GroupStorage/";
    private final String ELECTION_PATH = "/election";
    private final String INLINE_ELECTION_PATH = "/election/";

    private final String GROUPS_PATH = INLINE_BASE_PATH + "groups";
    private final String LEADERS_PATH = INLINE_BASE_PATH + "leaders";
    private final String DHT_PATH = INLINE_BASE_PATH + "dht";
    // Used by GroupMembers
    private final String CACHE_PATH = INLINE_BASE_PATH + "cache";
    private final String INLINE_GROUPS_PATH = GROUPS_PATH + "/";
    private final String INLINE_LEADERS_PATH = LEADERS_PATH + "/";
    private final String INLINE_DHT_PATH = DHT_PATH + "/";
    private final String INLINE_CACHE_PATH = CACHE_PATH + "/";

    private final Object2ObjectOpenHashMap<String, String> data = new Object2ObjectOpenHashMap<>();
    private final String uuid;
    private final Config configuration;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();
    private final Object2ObjectOpenHashMap<String, LeaderSelectorClient> leaderSelectorClientMap = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<String, VoronoiSitePoint> voronoiData = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<String, String> leaders = new Object2ObjectOpenHashMap<>();
    private final Multimap<String, String> dhtBootstrapHostnames = HashMultimap.create();
    private CuratorFramework client;
    private GroupMember groupMember;
    private CuratorCache leadersCache;
    private CuratorCache dhtCache;
    private AsyncCuratorFramework async;
    private VoronoiWrapper voronoiWrapper;
    private String groupName;
    private int baseSleepTimeMs = 200;
    private int maxRetries = 5;

    // <Group-ID: Leader Selector>
    private LeaderSelectorClient leaderSelectorClient;
    // GEO-location
    @Value("${node.group.voronoiGrouping}")
    private boolean voronoiGroupingEnabled;

    @Autowired
    public ZookeeperDirectoryServerClient(Config config) {
        this.configuration = config;
        this.zookeeperIp = config.getDirectoryServer().getHostname();
        this.groupName = "group-1";
        this.leaderSelectorClient = new LeaderSelectorClient();
        this.uuid = config.getPeerID();
        NetworkUtility.init();
    }

    private static <T, E> T getKeyByValue(Map<T, E> map, E value) {
        for (Map.Entry<T, E> entry : map.entrySet()) {
            if (Objects.equals(value, entry.getValue())) {
                return entry.getKey();
            }
        }
        throw new NoSuchElementException("no key for value: " + value);
    }

    public void setVoronoiGroupingEnabled(boolean voronoiGroupingEnabled) {
        this.voronoiGroupingEnabled = voronoiGroupingEnabled;
    }

    @Override
    public void init(double width, double height) throws Exception {
        logger.info("Starting Zookeeper client connection");
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(baseSleepTimeMs, maxRetries);
        if (voronoiGroupingEnabled) {
            voronoiWrapper = new VoronoiWrapper(height, width);
        }

        client = CuratorFrameworkFactory.newClient(zookeeperIp, retryPolicy);
        client.start();

        client.blockUntilConnected(60, TimeUnit.SECONDS);

        async = AsyncCuratorFramework.wrap(client);
        async.with(WatchMode.stateChangeAndSuccess).watched().getChildren().forPath(BASE_PATH).event().thenAccept(this::notifyZNodeCreation);
        async.with(WatchMode.stateChangeAndSuccess).watched().checkExists().forPath(INLINE_GROUPS_PATH).event().thenAccept(this::notifyZNodeCreation);
        async.with(WatchMode.stateChangeAndSuccess).watched().getChildren().forPath(INLINE_GROUPS_PATH + groupName).event().thenAccept(this::notifyZNodeCreation);

        createGroupStorageZNodes();
        createDhtCache();
        logger.info("Directory server client initialized successfully");
    }

    @Override
    public String getGroupName() {
        return groupName;
    }

    @Override
    public void close() {
        logger.debug("Curator client state = {}", client.getState().toString());
        if (client.getState() == CuratorFrameworkState.STOPPED) {
            logger.debug("Directory server already closed");
            return;
        }
        logger.warn("Closing Directory server client");
        try {
            leaderSelectorClientMap.get(groupName).close();
            if (leadersCache != null) {
                leadersCache.close();
            }
            if (dhtCache != null) {
                dhtCache.close();
            }
            client.close();
        } catch (IllegalStateException | IOException e) {
            logger.warn("Curator client threw an exception while closing");
        }
    }

    /**
     * Effectively the bootstrap call for the client
     * 1. Choose a fitting group<p>
     * 2. Creates a group-member<p>
     * 3. Creates leader selector client<p>
     * 4. Update group member data<p>
     *
     * @return {@link GroupData} i.e. group joined and peer-ID
     */
    @Override
    public GroupData joinGroup(VirtualPosition virtualPosition) throws Exception {
        logger.debug("Joining group");
        groupToJoin(virtualPosition);

        data.put("group", "");
        data.put("leader", "");
        data.put("peer", "");
        data.put("dht", "");

        try {
            createGroupMember(peerDataToByteArray());
            createLeaderSelectorClient();
        } catch (Exception e) {
            logger.error("Unable to join group! :(");
        }

        return GroupData.builder().groupName(groupName).peerId(uuid).build();
    }

    /**
     * Effectively the bootstrap call for the client
     * 1. Choose a fitting group<p>
     * 2. Creates a group-member<p>
     * 3. Creates leader selector client<p>
     * 4. Update group member data<p>
     *
     * @return {@link GroupData} i.e. group joined and peer-ID
     */
    @Override
    public GroupData newGroupLeader(VirtualPosition virtualPosition) throws Exception {
        logger.debug("Joining group");
        int groupToLead = getNumberOfGroups() + 1;
        groupName = "group-" + groupToLead;

        data.put("group", "");
        data.put("leader", "");
        data.put("peer", "");
        data.put("dht", "");

        try {
            createGroupMember(peerDataToByteArray());
            createLeaderSelectorClient();
        } catch (Exception e) {
            logger.error("Unable to join group! :(");
        }

        return GroupData.builder().groupName(groupName).peerId(uuid).build();
    }

    /**
     * 1. Close the current group-member (Destroy znode)<p>
     * 2. Update the group name<p>
     * 3. Create new member with updated data<p>
     * 4. Create the new leader selector client<p>
     *
     * @param group to join
     * @throws Exception
     */
    @Override
    public void migrateMember(String group) throws Exception {
        if (!group.equals(groupName)) {
            groupMember.close();
            groupName = group;
            createGroupMember(peerDataToByteArray());
            createLeaderSelectorClient();
        }
    }

    public Object2ObjectOpenHashMap<String, String> getDataMap() {
        return data;
    }

    @Override
    public String getGroupLeader(String group) throws Exception {
        String leaderHostname = "";
        LeaderSelectorClient leaderSelectorClient = leaderSelectorClientMap.get(group);
        if (leaderSelectorClient != null) {
            FutureTask<String> future = new FutureTask<>(leaderSelectorClient::getLeader);
            future.run();
            leaderHostname = future.get(2000, TimeUnit.MILLISECONDS);
        }
        return leaderHostname;
    }

    @Override
    public String getGroupLeaderId(String group) throws Exception {
        logger.debug("Getting leader ID for {}", group);
        String leaderHostname = "";
        if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
            RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();

            try {
                while (retryLoop.shouldContinue()) {
                    try {
                        client.sync().forPath(INLINE_LEADERS_PATH + group);
                        ObjectArrayList<String> leaderData = new ObjectArrayList<>(client.getChildren().forPath(INLINE_LEADERS_PATH + group));
                        logger.debug("leaders for {} - {}", group, leaderData);
                        leaderHostname = leaderData.iterator().next();
                        retryLoop.markComplete();
                    } catch (Exception e) {
                        retryLoop.takeException(new KeeperException.ConnectionLossException());
                        retryLoop.shouldContinue();
                    }
                    logger.debug("retrying `getGroupLeaderId`...");
                }
            } catch (Exception e) {
                logger.warn("Group does not exist: {}", group);
                leaders.remove(group);
                voronoiData.remove(group);
            }
        }

        return leaderHostname;
    }

    @Override
    public NeighbourData getNeighbouringLeaderData(VirtualPosition virtualPosition) throws Exception {
        if (voronoiGroupingEnabled && voronoiWrapper.voronoiMapGenerated()) {
            return voronoiNeighbourData(virtualPosition);
        } else {
            return randomNeighbourData();
        }
    }

    /**
     * Voronoi grouping disabled
     *
     * @return data of the next super-peer group
     * i.e. group-2 -> neighbours [group-1, group-3]
     */
    private NeighbourData randomNeighbourData() throws Exception {
        int count = Integer.parseInt(groupName.split("-")[1]);
        String neighbour1GroupName = "group-" + (count + 1);
        String neighbour1 = getGroupLeaderId(neighbour1GroupName);

        if (!neighbour1.isEmpty()) {
            return NeighbourData.builder().groupName(neighbour1GroupName).leaderData(getLeaderData(neighbour1GroupName, neighbour1)).build();
        } else {
            String neighbour2GroupName = "group-" + (count - 1);
            String neighbour2 = getGroupLeaderId(neighbour2GroupName);
            if (!neighbour2.isEmpty()) {
                return NeighbourData.builder().groupName(neighbour2GroupName).leaderData(getLeaderData(neighbour2GroupName, neighbour2)).build();
            }
        }
        return NeighbourData.defaultInstance();
    }

    /**
     * Voronoi grouping enabled
     *
     * @return data of the voronoi neighbour
     * i.e. checks the voronoi map for new super-peer node
     */
    private NeighbourData voronoiNeighbourData(VirtualPosition virtualPosition) {
        // get this nodes voronoi data
        NeighbourData.NeighbourDataBuilder neighbourDataBuilder = NeighbourData.builder();

        try {
            String newGroupLeaderHostname = voronoiWrapper.findVoronoiGroupToJoin(virtualPosition);
            String newGroupName = getKeyByValue(leaders, newGroupLeaderHostname);
            neighbourDataBuilder.leaderData(VoronoiSitePoint.builder().hostname(newGroupLeaderHostname).build());
            neighbourDataBuilder.groupName(newGroupName);
        } catch (IllegalStateException e) {
            logger.error("Error when retrieving Voronoi group data");
        }

        return neighbourDataBuilder.build();
    }

    @Override
    public VoronoiSitePoint getLeaderData(String group, String id) throws Exception {
        // might break if leader changes in this time
        byte[] leaderData = "".getBytes(StandardCharsets.UTF_8);
        try {
            if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
                RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();

                while (retryLoop.shouldContinue()) {
                    try {
                        logger.info("syncing: {}", INLINE_LEADERS_PATH + group);
                        client.sync().forPath(INLINE_LEADERS_PATH + group);
                        leaderData = client.getData().forPath(INLINE_LEADERS_PATH + group + "/" + id);
                        if (leaderData != null) {
                            retryLoop.markComplete();
                        }
                    } catch (Exception e) {
                        retryLoop.takeException(new KeeperException.ConnectionLossException());
                        retryLoop.shouldContinue();
                    }
                    logger.info("retrying `getLeaderData`...");
                }
            }
        } catch (Exception e) {
            logger.warn("Leader doesn't exist: {}", group);
            leaders.remove(group);
            voronoiData.remove(group);
            throw new NoSuchElementException("Leader doesn't exist: " + group);
        }

        VoronoiSitePoint leaderHostname = objectMapper.readValue(leaderData, VoronoiSitePoint.class);
        logger.debug("Found Leader at: {}", leaderHostname.getHostname());
        return leaderHostname;
    }

    @Override
    public void setGroupStorageHostname(String host) throws Exception {
        data.replace("group", host);
        logger.debug("Setting group data map: {}", data);

        if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
            RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();
            while (retryLoop.shouldContinue()) {
                try {
                    logger.debug("Setting /GroupStorage/dht/{}/{} with {}", groupName, uuid, host);
                    groupMember.setThisData(peerDataToByteArray());
                    client.setData().forPath(INLINE_GROUPS_PATH + groupName + "/" + uuid, host.getBytes(StandardCharsets.UTF_8));
                    retryLoop.markComplete();
                } catch (Exception e) {
                    retryLoop.takeException(new KeeperException.ConnectionLossException());
                    retryLoop.shouldContinue();
                }
                logger.info("retrying directory server command: `setGroupStorageHostname`...");
            }
        }
    }

    @Override
    public void setPeerHostname(String host) throws Exception {
        data.replace("peer", host);
        logger.debug("Setting Zookeeper data: {}", data);
        if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
            RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();
            while (retryLoop.shouldContinue()) {
                try {
                    logger.info("Setting /GroupStorage/dht/{}/{} with {}", groupName, uuid, host);
                    groupMember.setThisData(peerDataToByteArray());
                    retryLoop.markComplete();
                } catch (Exception e) {
                    retryLoop.takeException(new KeeperException.ConnectionLossException());
                    retryLoop.shouldContinue();
                }
                logger.info("retrying directory server command: `setPeerHostname`...");
            }
        }
    }

    @Override
    public void setDHTHostname(String host) throws Exception {
        data.replace("dht", host);
        logger.info("Setting dht data: {}", host);
        logger.info("Setting Zookeeper data: {}", data);

        if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
            RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();
            while (retryLoop.shouldContinue()) {
                try {
                    logger.info("Setting /GroupStorage/dht/{}/{} with {}", groupName, uuid, host);
                    client.setData().forPath(INLINE_DHT_PATH + groupName + "/" + uuid, host.getBytes(StandardCharsets.UTF_8));
                    retryLoop.markComplete();
                } catch (Exception e) {
                    retryLoop.takeException(new KeeperException.ConnectionLossException());
                    retryLoop.shouldContinue();
                }
                logger.info("retrying directory server command: `setDHTHostname`...");
            }
        }
        groupMember.setThisData(peerDataToByteArray());
    }

    @Override
    public void setLeaderHostname(String host) throws Exception {
        LeaderSelectorClient leaderClient = leaderSelectorClientMap.get(groupName);
        VoronoiSitePoint newData = VoronoiSitePoint.builder().hostname(host).build();
        String jsonData = objectMapper.writeValueAsString(newData);
        data.replace("leader", host);
        logger.debug("Setting leader data: {}", jsonData);
        logger.debug("Setting member data: {}", data);
        logger.debug("Setting /GroupStorage/leaders/{}/{} with {}", groupName, uuid, host);

        try {
            if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
                RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();
                while (retryLoop.shouldContinue()) {
                    try {
                        client.setData().forPath(INLINE_LEADERS_PATH + groupName + "/" + uuid, jsonData.getBytes(StandardCharsets.UTF_8));
                        groupMember.setThisData(peerDataToByteArray());
                        retryLoop.markComplete();
                    } catch (Exception e) {
                        retryLoop.takeException(new KeeperException.ConnectionLossException());
                        retryLoop.shouldContinue();
                    }
                    logger.info("retrying `setLeaderHostname`...");
                }
            }
        } catch (Exception exception) {
            logger.error("Leader or group doesn't exist, creating z-node");
            createLeaderZNode(leaderClient);
            exception.printStackTrace();
        }
    }

    @Override
    public void setLeaderVoronoiSitePoint(String host, double x, double y) throws Exception {
        LeaderSelectorClient leaderClient = leaderSelectorClientMap.get(groupName);
        VoronoiSitePoint newData = new VoronoiSitePoint(host, x, y);
        String jsonData = objectMapper.writeValueAsString(newData);
        logger.debug("Setting leader data: {}", jsonData);
        data.replace("leader", host);
        logger.debug("Setting member data: {}", data);
        logger.debug("Setting /GroupStorage/leaders/{}/{} with {}", groupName, uuid, host);

        try {
            if (client.getZookeeperClient().blockUntilConnectedOrTimedOut()) {
                RetryLoop retryLoop = client.getZookeeperClient().newRetryLoop();

                while (retryLoop.shouldContinue()) {
                    try {
                        client.sync().forPath(INLINE_LEADERS_PATH + groupName);
                        client.setData().forPath(INLINE_LEADERS_PATH + groupName + "/" + uuid, jsonData.getBytes(StandardCharsets.UTF_8));
                        retryLoop.markComplete();
                    } catch (Exception e) {
                        retryLoop.takeException(new KeeperException.ConnectionLossException());
                        retryLoop.shouldContinue();
                    }
                    logger.info("retrying `setLeaderVoronoiSitePoint`...");
                }
            }
        } catch (Exception exception) {
            logger.error("Leader or group doesn't exist, creating z-node");
            createLeaderZNode(leaderClient);
            exception.printStackTrace();
        }
        groupMember.setThisData(peerDataToByteArray());
    }

    @Override
    public ObjectList<String> getGroupStorageHostnames(String group) throws Exception {
        logger.debug("Getting group storage hostnames...");
        ObjectList<String> hosts = extractDataFromGroup(ZooDatatype.GROUP);
        logger.debug("Group storage hosts: {}", Arrays.toString(hosts.toArray()));
        return hosts;
    }

    @Override
    public ObjectList<String> getPeerHostnames(String group) throws Exception {
        logger.debug("Getting peer hostnames...");
        ObjectList<String> hosts = extractDataFromGroup(ZooDatatype.PEER);
        logger.debug("Peer hosts: {}", Arrays.toString(hosts.toArray()));
        return hosts;
    }

    @Override
    public String getDHTHostnamesFromGroupMember(String group) throws IOException, ClassNotFoundException {
        ObjectList<String> dhtHostnames = extractDataFromGroup(ZooDatatype.DHT);
        logger.info("DHT data from group member: {}", dhtHostnames);
        return dhtHostnames.iterator().hasNext() ? dhtHostnames.iterator().next() : "";
    }

    @Override
    public String getGroupMemberDHTHostname(String groupName) throws Exception {
        ObjectList<String> hostnamesFromCacheMap = new ObjectArrayList<>(dhtBootstrapHostnames.get(groupName));
        Collections.shuffle(hostnamesFromCacheMap, random);

        if (!hostnamesFromCacheMap.isEmpty()) {
            logger.debug("DHT hostname: {} ({})", hostnamesFromCacheMap, groupName);
            for (String hostname : hostnamesFromCacheMap) {
                if (!hostname.equals(configuration.getNetworkHostnames().getOverlayServer())) {
                    logger.info("DHT hostname from curator cache: {}", hostname);
                    return hostname;
                }
            }
        }
        logger.info("DHT Cache did not contain any mappings!");
        return getGroupMemberDHTHostnameFromPath(groupName);
    }

    private String getGroupMemberDHTHostnameFromPath(String groupName) throws Exception {
        ObjectArrayList<String> groupClients = new ObjectArrayList<>(client.getChildren().forPath(INLINE_DHT_PATH + groupName));
        logger.info("DHT data for {}: {}", groupName, groupClients);

        for (String id : groupClients) {
            byte[] groupMemberData = client.getData().forPath(INLINE_DHT_PATH + groupName + "/" + id);
            String data = new String(groupMemberData, StandardCharsets.UTF_8);
            if (!data.contains("localhost") || !data.contains("127.")) {
                logger.info("The extracted hostname data: {}", data);
                return data;
            }
        }
        return "";
    }

    @Override
    public ObjectOpenHashSet<String> getGroupMemberIDs() {
        return new ObjectOpenHashSet<>(groupMember.getCurrentMembers().keySet());
    }

    @Override
    public String extractDataForGroupMember(ZooDatatype dataKey, String id) throws IOException, ClassNotFoundException {

        Map<String, byte[]> members = groupMember.getCurrentMembers();

        byte[] value = members.get(id);
        ByteArrayInputStream byteIn = new ByteArrayInputStream(value);
        ObjectInputStream in = new ObjectInputStream(byteIn);

        Object2ObjectOpenHashMap<String, String> dataAsMap = (Object2ObjectOpenHashMap<String, String>) in.readObject();
        in.close();
        String extractedValue = dataAsMap.get(dataKey.getValue());

        logger.debug("{} for Member {}: {}", dataKey.getValue().toUpperCase(), id, extractedValue);
        if (!extractedValue.isEmpty()) {
            logger.warn("Could not extract anything");
        }

        return extractedValue;
    }

    @Override
    public boolean isWithinAOI(VirtualPosition virtualPosition) {
        if (voronoiGroupingEnabled && voronoiWrapper.voronoiMapGenerated()) {
            String aoiGroup = voronoiWrapper.findVoronoiGroupToJoin(virtualPosition);
            if (configuration.getNetworkHostnames().getSuperPeerServer().equals(aoiGroup)) {
                return true;
            } else {
                logger.info("{} falls within {} and should be migrated", virtualPosition, aoiGroup);
                return false;
            }
        }
        return true;
    }

    public void notifyZNodeCreation(WatchedEvent event) {
        logger.debug("Z-node Created: " + event.getType());
    }

    public void notifyZNodeDataChanged(WatchedEvent event) {
        logger.debug("data has been modified: " + event.getType());
    }

    public boolean isConnected() {
        return client.getState().equals(CuratorFrameworkState.STARTED);
    }

    private void createGroupStorageZNodes() {
        // Curator issue: CuratorTransaction doesn't handle parent nodes gracefully, so parent nodes need to be created first
        logger.info("Creating required z-nodes");
        createZNode(BASE_PATH, CreateMode.PERSISTENT);
        createZNode(ELECTION_PATH, CreateMode.PERSISTENT);
        createZNode(LEADERS_PATH, CreateMode.PERSISTENT);
        createZNode(GROUPS_PATH, CreateMode.PERSISTENT);
        createZNode(CACHE_PATH, CreateMode.PERSISTENT);
        createZNode(DHT_PATH, CreateMode.PERSISTENT);
    }

    private void createLeadersCache() {
        CuratorCacheListener listener = CuratorCacheListener.builder()
                .forCreates(node -> {
                    logger.debug(String.format("Node created: [%s]", node.getPath()));
                    LeaderNodeData data = LeaderNodeDataParser.parseLeaderNodeDataFromPath(node.getPath()).build();
                    if (data.getGroupName() != null && data.getLeaderId() != null) {
                        addLeader(data.getGroupName(), data.getLeaderId());
                    }
                })
                .forChanges((oldNode, node) -> {
                    logger.info(String.format("Node changed. Old: [%s] New: [%s]", new String(oldNode.getData(), StandardCharsets.UTF_8), new String(node.getData(), StandardCharsets.UTF_8)));
                    LeaderNodeData data = parseLeaderNodeData(node);
                    if (data.getGroupName() != null && data.getHostname() != null && !(data.getHostname().contains("127.0.0.1") || data.getHostname().contains("localhost"))) {
                        updateSiteData(data.getGroupName(), data.getHostname(), data.getX(), data.getY());
                    }
                })
                .forDeletes(oldNode -> {
                    logger.info(String.format("Node deleted. Old value: [%s]", oldNode.getPath()));
                    LeaderNodeData data = LeaderNodeDataParser.parseLeaderNodeDataFromPath(oldNode.getPath()).build();
                    if (data.getGroupName() != null) {
                        removeLeader(data.getGroupName());
                    }
                })
                .forInitialized(() -> logger.info("Leader cache initialized"))
                .build();

        leadersCache = CuratorCache.build(client, LEADERS_PATH, CuratorCache.Options.DO_NOT_CLEAR_ON_CLOSE);
        leadersCache.listenable().addListener(listener);
        leadersCache.start();
    }

    private void createDhtCache() {
        CuratorCacheListener listener = CuratorCacheListener.builder()
                .forCreates(node -> {
                    logger.debug("DHT Node created, {}", node.getPath());
                    DhtNodeData data = DhtNodeDataParser.parseDhtNodeDataFromPath(node.getPath()).build();
                    String group = data.getGroupName();
                    String id = data.getId();
                    if (group != null && id != null) {
                        logger.debug("DHT Node node created - {} : {}", group, id);
                    }
                })
                .forChanges((oldNode, node) -> {
                    logger.debug("DHT Node changed, Old: {} New: {}", new String(oldNode.getData(), StandardCharsets.UTF_8), new String(node.getData(), StandardCharsets.UTF_8));
                    DhtNodeData data = DhtNodeDataParser.parseDhtNodeData(node);
                    String group = data.getGroupName();
                    String hostname = data.getHostname();
                    if (group != null && hostname != null) {
                        logger.debug("Adding dht node - {} : {}", group, hostname);
                        dhtBootstrapHostnames.put(group, hostname);
                    }
                })
                .forDeletes(oldNode -> {
                    logger.debug("DHT Node deleted. Old value: {}", oldNode.getPath());
                    DhtNodeData data = DhtNodeDataParser.parseDhtNodeData(oldNode);
                    String group = data.getGroupName();
                    String hostname = data.getHostname();
                    if (group != null && hostname != null) {
                        logger.debug("Removing dht node: {} - {}", group, hostname);
                        dhtBootstrapHostnames.remove(group, hostname);
                    }
                })
                .forInitialized(() -> logger.debug("DHT cache initialized"))
                .build();

        dhtCache = CuratorCache.build(client, DHT_PATH, CuratorCache.Options.DO_NOT_CLEAR_ON_CLOSE);
        dhtCache.listenable().addListener(listener);
        dhtCache.start();
    }

    public void addLeader(@NonNull String groupName, @NonNull String leaderId) {
        logger.info("Leader added: {} -> {}", groupName, leaderId);
    }

    public void removeLeader(@NonNull String groupName) {
        if (voronoiData.containsKey(groupName)) {
            voronoiWrapper.removeSuperPeerLocation(voronoiData.get(groupName));
            voronoiData.remove(groupName);
            leaders.remove(groupName);
        } else {
            voronoiData.remove(groupName);
            leaders.remove(groupName);
            updateVoronoiMap();
        }
    }

    public void updateSiteData(@NonNull String groupName, @NonNull String hostname, double x, double y) {
        voronoiData.put(groupName, new VoronoiSitePoint(hostname, x, y));
        leaders.put(groupName, hostname);
        updateVoronoiMap();
    }


    private void updateVoronoiMap() {
        if (voronoiGroupingEnabled) {
            populateMapFromDirectoryServer();
            voronoiWrapper.updateVoronoiMap(new ObjectArrayList<>(voronoiData.values()));
        }
    }

    private void collectDataFromCache() {
        leadersCache.stream().forEach(childData -> {
            LeaderNodeData data = parseLeaderNodeData(childData);
            System.out.println(data);
        });
    }

    // This is required because we don't get notified of nodes that have been running before this one
    private void populateMapFromDirectoryServer() {
        if (leaders.isEmpty() || leaders.size() == 1) {
            logger.info("Populating voronoi leaders map for the first time!");
            callGroupDataDirectly();
        }

        leaders.forEach((group, id) -> {
            if (!voronoiData.containsKey(group)) {
                try {
                    VoronoiSitePoint data = getLeaderData(group, id);
                    voronoiData.put(group, data);
                } catch (Exception exception) {
                    logger.info("Could not get leader data");
                    exception.printStackTrace();
                }
            } else {
                logger.debug("Key {} already contained in voronoi-data map", group);
            }
        });

        logger.debug("Leaders map populated: {}", leaders);
    }

    private void callGroupDataDirectly() {
        Object2ObjectOpenHashMap<String, String> tempLeaders = new Object2ObjectOpenHashMap<>();
        try {
            getGroupNames().forEach(groupName -> {
                try {
                    String leaderId = getGroupLeaderId(groupName);
                    if (leaderId != null) {
                        tempLeaders.put(groupName, leaderId);
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            });

            if (!tempLeaders.isEmpty()) {
                tempLeaders.forEach((groupName, leaderId) -> {
                    try {
                        VoronoiSitePoint data = getLeaderData(groupName, leaderId);
                        voronoiData.put(groupName, data);
                        leaders.put(groupName, data.getHostname());
                    } catch (Exception exception) {
                        logger.error("could not retrieve leader data!");
                        exception.printStackTrace();
                    }
                });
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void createZNode(String path, CreateMode createMode) {
        if (zNodeExists(path)) {
            logger.info("Z-node already exists for path: {}", path);
        } else {
            logger.info("Creating z-node for path: {}", path);
            try {
                client.create().creatingParentContainersIfNeeded().withMode(createMode).forPath(path);
                logger.info("Z-node created for path: {}", path);
            } catch (Exception e) {
                logger.error("Failed to create Z-node for path: {}", path);
            }
        }
    }

    private void createNextGroupStorageZNode() {
        // Curator issue: CuratorTransaction doesn't handle parent nodes gracefully, so parent nodes need to be created first
        // String newGroupName = "group-" + groupCount;
        logger.info("Creating new z-nodes /GroupStorage/<groups><cache>/{}", groupName);
        createZNode(INLINE_GROUPS_PATH + groupName, CreateMode.CONTAINER);
        createZNode(INLINE_CACHE_PATH + groupName, CreateMode.CONTAINER);
        createZNode(INLINE_DHT_PATH + groupName, CreateMode.CONTAINER);

        LeaderSelectorClient selectorClient = new LeaderSelectorClient();

        logger.info("Adding Leader selector client for {}", groupName);
        this.leaderSelectorClientMap.put(groupName, selectorClient);
        this.leaderSelectorClient = selectorClient;
    }

    private boolean zNodeExists(String path) {
        try {
            return client.checkExists().forPath(path) != null;
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error occurred whilst checking if znode exists!");
        } catch (Exception e) {
            logger.error("Something else went wrong!");
        }
        return false;
    }

    private boolean zNodeExistsAsync(String path) {
        try {
            return async.checkExists().forPath(path).toCompletableFuture().get() != null;
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error occurred whilst checking if znode exists!");
        } catch (Exception e) {
            logger.error("Something else went wrong!");
        }
        return false;
    }

    private void createGroupMember(byte[] data) throws InterruptedException {
        logger.debug("Assigning group member id: {}", uuid);
        createZNode(INLINE_GROUPS_PATH + groupName + "/" + uuid, CreateMode.EPHEMERAL);
        createZNode(INLINE_DHT_PATH + groupName + "/" + uuid, CreateMode.EPHEMERAL);

        logger.debug("creating groupMember for path: {}/{}", INLINE_CACHE_PATH + groupName, uuid);
        groupMember = new GroupMember(client, INLINE_CACHE_PATH + groupName, uuid, data);
        groupMember.start();
        boolean waitUntilInitialCreate = zNodeExists(INLINE_CACHE_PATH + groupName + "/" + uuid);
        while (!waitUntilInitialCreate) {
            Thread.sleep(5);
            waitUntilInitialCreate = zNodeExists(INLINE_CACHE_PATH + groupName + "/" + uuid);
        }
        logger.info("GroupMembers: {}", groupMember.getCurrentMembers().keySet());
    }

    // TODO ugly code.
    private void createLeaderSelectorClient() throws Exception {
        logger.info("Assigning Leader client...");
        LeaderSelectorClient leaderClient;
        if (!leaderSelectorClientMap.containsKey(groupName)) {
            logger.debug("Leader Selector not found for {}", groupName);
            leaderClient = leaderSelectorClient;
        } else {
            leaderClient = leaderSelectorClientMap.get(groupName);
        }

        leaderSelectorClientMap.put(groupName, leaderClient);

        LeaderLatchListener listener = new LeaderLatchListener() {
            @Override
            public void isLeader() {
                logger.info("Acquiring leadership!");
                createLeaderZNode(leaderClient);
                createLeadersCache();
            }

            @Override
            public void notLeader() {
                logger.info("Relinquishing leadership!");
            }
        };

        leaderClient.start(client, INLINE_ELECTION_PATH + groupName, uuid, listener);
        leaderSelectorClient = leaderClient;
    }

    private void createLeaderZNode(LeaderSelectorClient leaderClient) {
        logger.debug("Checking to see if this client is the leader");

        if (leaderClient.hasLeadership()) {
            logger.info("Creating leader node");
            createZNode(INLINE_LEADERS_PATH + groupName + "/" + uuid, CreateMode.EPHEMERAL);
        } else {
            logger.error("Leader selector did not seem to have leadership!");
        }
    }

    private void groupToJoin(VirtualPosition position) throws Exception {
        logger.debug("Assigning to group");
        int groupNumberToJoin = 1;
        try {
            int groupCount = getNumberOfGroups();
            logger.info("Group count is: {}", groupCount);
            if (groupCount != 0) {
                if (voronoiGroupingEnabled) {
                    logger.debug("Assigning to voronoi group");
                    voronoiGroupToJoin(position, groupNumberToJoin, groupCount);
                } else {
                    logger.debug("Assigning to random group");
                    randomGroupToJoin(groupNumberToJoin, groupCount);
                }
            } else {
                logger.warn("No group nodes exists - setting group name to default: group-1");
                groupName = "group-" + groupNumberToJoin;
            }
        } catch (KeeperException.NoNodeException e) {
            logger.warn("Base path for groups not found!");
            throw new IllegalStateException("Please ensure all required base paths were created!");
        }
    }

    /**
     * Voronoi grouping disabled
     */
    private void randomGroupToJoin(int groupNumberToJoin, int groupCount) {
        ObjectList<String> joinableGroups = getAnyJoinableGroups();
        logger.info("Joinable groups: {}", joinableGroups);
        if (joinableGroups.isEmpty()) {
            groupNumberToJoin = ++groupCount;
            createNewGroup(groupNumberToJoin);
        } else {
            groupName = joinableGroups.iterator().next();
        }
    }

    /**
     * Voronoi grouping enabled
     */
    private void voronoiGroupToJoin(VirtualPosition position, int groupNumberToJoin, int groupCount) {
        ObjectList<String> joinableGroups;
        // Initialise the voronoi map by collecting the site-points of each super-peer and generating the map
        updateVoronoiMap();
        if (voronoiWrapper.voronoiMapGenerated()) {
            joinableGroups = getJoinableVoronoiGroups(position);
        } else {
            logger.error("Voronoi map not generated, need al-least 2 site-points");
            joinableGroups = getAnyJoinableGroups();
        }

        logger.info("Joinable groups: {}", joinableGroups);

        if (joinableGroups.isEmpty()) {
            groupNumberToJoin = ++groupCount;
            createNewGroup(groupNumberToJoin);
        } else {
            groupName = joinableGroups.iterator().next();
        }
    }

    private void createNewGroup(int groupNumberToJoin) {
        // A new group is required
        groupName = "group-" + groupNumberToJoin; // this count could be any other id
        logger.info("New group required, Group to join is: {}", groupName);
        createNextGroupStorageZNode();
    }

    /**
     * TODO: this will not scale
     * <p>
     * Voronoi grouping disabled
     */
    protected ObjectList<String> getAnyJoinableGroups() {
        ObjectList<String> possibleGroupsToJoin = new ObjectArrayList<>();
        try {
            getGroupNames().forEach(group -> {
                try {
                    int childrenCount = getGroupChildrenCount(group);
                    if (childrenCount < configuration.getMaxPeers()) {
                        // TODO add logic for checking location data of the node
                        possibleGroupsToJoin.add(group);
                    }
                } catch (Exception e) {
                    logger.error("Couldn't retrieve group children count");
                }
            });
        } catch (Exception e) {
            logger.error("Couldn't retrieve group names");
        }
        logger.info("Joinable groups are: {}", possibleGroupsToJoin);
        return possibleGroupsToJoin;
    }

    /**
     * Voronoi grouping enabled
     */
    protected ObjectList<String> getJoinableVoronoiGroups(VirtualPosition position) {
        ObjectList<String> possibleGroupsToJoin = new ObjectArrayList<>();
        try {
            // currently we store the IP as the id of voronoi groups
            // this means we need to translate that to a group-name before we return the list of available groups
            String voronoiGroupToJoin = voronoiWrapper.findVoronoiGroupToJoin(position);
            String actualGroupName = getKeyByValue(leaders, voronoiGroupToJoin);
            logger.info("Voronoi group is: {}", actualGroupName);
            if (hasAvailablePositions(actualGroupName)) {
                possibleGroupsToJoin.add(actualGroupName);
            } else {
                VirtualPosition sitePointToJoin = voronoiWrapper.findVoronoiGroupPointToJoin(position);
                possibleGroupsToJoin = new ObjectArrayList<>(voronoiWrapper.getNeighbours(sitePointToJoin)
                        .stream()
                        .map(groupIP -> getKeyByValue(leaders, groupIP))
                        .filter(neighbourGroup -> hasAvailablePositions(neighbourGroup))
                        .collect(Collectors.toList()));
            }
        } catch (Exception e) {
            logger.error("No joinable  voronoi groups exist!");
        }
        logger.info("Joinable voronoi groups: {}", possibleGroupsToJoin);
        return possibleGroupsToJoin;
    }

    private boolean hasAvailablePositions(String group) {
        try {
            int childrenCount = getGroupChildrenCount(group);
            logger.info("{} has {} children", group, childrenCount);
            logger.info("max peers for {} -> {}", group, configuration.getMaxPeers());
            return childrenCount < configuration.getMaxPeers();
        } catch (Exception e) {
            logger.error("{} has no available positions", group);
            return false;
        }
    }

    private ObjectList<String> extractDataFromGroup(ZooDatatype dataKey) throws IOException, ClassNotFoundException {
        ObjectArrayList<String> requestedValues = new ObjectArrayList<>();
        Map<String, byte[]> members = groupMember.getCurrentMembers();

        for (String member : members.keySet()) {
            byte[] value = members.get(member);
            ByteArrayInputStream byteIn = new ByteArrayInputStream(value);
            ObjectInputStream in = new ObjectInputStream(byteIn);

            @SuppressWarnings("unchecked")
            Object2ObjectOpenHashMap<String, String> dataAsMap = (Object2ObjectOpenHashMap<String, String>) in.readObject();

            in.close();
            String extractedValue = dataAsMap.get(dataKey.getValue());

            logger.debug("{} for Member {}: {}", dataKey.getValue().toUpperCase(), member, extractedValue);
            if (!extractedValue.isEmpty()) {
                requestedValues.add(extractedValue);
            }
        }
        return requestedValues;
    }

    private int getGroupChildrenCount(int groupNumber) throws Exception {
        return client.getChildren().forPath(INLINE_CACHE_PATH + "group-" + groupNumber).size();
    }

    private int getGroupChildrenCount(String groupName) throws Exception {
        if (!checkIfGroupExists(groupName)) {
            logger.error("the group doesn't exist fool!");
        }
        return client.getChildren().forPath(INLINE_CACHE_PATH + groupName).size();
    }

    private ObjectList<String> getGroupNames() throws Exception {
        client.sync().forPath(CACHE_PATH);
        return new ObjectArrayList<>(client.getChildren().forPath(CACHE_PATH));
    }

    protected int getNumberOfGroups() throws Exception {
        client.sync().forPath(CACHE_PATH);
        return client.getChildren().forPath(CACHE_PATH).size();
    }

    private boolean checkIfGroupExists(String groupName) {
        return zNodeExists(INLINE_GROUPS_PATH + groupName);
    }

    private byte[] peerDataToByteArray() {
        if (data.values().isEmpty()) {
            return "".getBytes(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out;
        try {
            out = new ObjectOutputStream(byteOut);
            out.writeObject(data);
            return byteOut.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "".getBytes(StandardCharsets.UTF_8);
    }

    public void setBaseSleepTimeMs(int baseSleepTimeMs) {
        this.baseSleepTimeMs = baseSleepTimeMs;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
