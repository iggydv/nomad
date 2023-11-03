package org.nomad.pithos.components;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import it.unimi.dsi.fastutil.objects.HashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.delegation.DirectoryServerClient;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.grpc.management.clients.PeerClient;
import org.nomad.grpc.management.services.callables.HandleSuperPeerLeaveCallable;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.nomad.pithos.models.MetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1. handling joining peers,
 * 2. facilitating peer migration,
 * 3. ensuring group consistency (Section 5.3.5),
 * 4. initiating object repair, and
 * 5. maintaining a list of group objects and a list of peers on which those objects are stored (Section 5.2.2.4)
 */
@Service
@DependsOn("ZookeeperDirectoryServerClient")
public class SuperPeer {
    private final Logger logger = LoggerFactory.getLogger(SuperPeer.class);

    private final HashMap<String, PeerClient> clients = new HashMap<>();
    private final HashMap<String, String> peerServer_groupStorageServer = new HashMap<>();
    // inverse for constant lookup
    private final HashMap<String, String> groupStorageServer_peerServer = new HashMap<>();
    private final Config configuration;
    private final DirectoryServerClient directoryServerClient;
    private final String superPeerIp;
    private final int superPeerPort;
    private GenericGroupLedger groupLedger;
    private String groupName;
    private boolean running = false;

    @Autowired
    public SuperPeer(DirectoryServerClient directoryServerClient, Config configuration) {
        NetworkUtility.init();
        this.groupLedger = GroupLedger.getInstance();
        this.directoryServerClient = directoryServerClient;
        this.groupName = directoryServerClient.getGroupName();
        this.superPeerIp = NetworkUtility.getIP();
        this.superPeerPort = NetworkUtility.randomPort(5001, 8999);
        this.configuration = configuration;
    }

    public boolean isWithinAOI(VirtualPosition position) {
        return directoryServerClient.isWithinAOI(position);
    }

    public NeighbourData getNeighbourSuperPeer(VirtualPosition virtualPosition) throws Exception {
        NeighbourData neighbour = directoryServerClient.getNeighbouringLeaderData(virtualPosition);
        return (neighbour.getLeaderData() != null || neighbour.getGroupName() != null) ? neighbour : NeighbourData.defaultInstance();
    }

    public boolean handleJoin(String newPeer, String newGroupStoragePeer) {
        int maxPeers = configuration.getMaxPeers();

        if (clients.keySet().size() >= maxPeers) {
            logger.error("Peer limit ({}) reached, cannot accept peer", maxPeers);
            return false;
        }

        ManagedChannel channel = ManagedChannelBuilder.forTarget(newPeer).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
        PeerClient client = new PeerClient(channel);
        logger.info("Adding to list of available Peers: {}", newPeer);

        clients.putIfAbsent(newPeer, client);
        peerServer_groupStorageServer.putIfAbsent(newPeer, newGroupStoragePeer);
        groupStorageServer_peerServer.putIfAbsent(newGroupStoragePeer, newPeer);

        logger.info("Available clients: {}", clients.keySet());
        logger.info("Available group-storage clients: {}", peerServer_groupStorageServer.values());
        return true;
    }

    /**
     * Handle Peer Leave
     */
    public boolean handleLeave(String optionalPeerServer, String groupStorageServer) {
        boolean result = false;
        String peerServer = optionalPeerServer.isEmpty() ? groupStorageServer_peerServer.get(groupStorageServer) : optionalPeerServer;
        logger.debug("Group storage hostname to remove: {}", groupStorageServer);

        if (clients.containsKey(peerServer) && peerServer != null) {
            clients.remove(peerServer);
            if (peerServer_groupStorageServer.containsValue(groupStorageServer)) {
                peerServer_groupStorageServer.remove(peerServer);
                groupStorageServer_peerServer.remove(groupStorageServer);
                clients.values().forEach(client -> {
                    client.removePeer(groupStorageServer);
                });
                result = true;
            } else {
                logger.warn("The group-server: {} is not associated with peer-server: {}", groupStorageServer, peerServer);
                logger.warn("KV - {} : {}", peerServer, peerServer_groupStorageServer.get(peerServer));
                logger.warn("Not removed from group-storage!");
                result = false;
            }
        } else {
            logger.warn("{} is not contained in PeerServer map", peerServer);
        }
        scheduledRepair();
        return result;
    }

    @Scheduled(fixedRateString = "${spring.schedule.repair}")
    public void scheduledRepair() {
        AtomicBoolean busy = new AtomicBoolean(false);
        if (running && !clients.isEmpty() && !busy.get()) {
            busy.set(true);
            int rf = configuration.getStorage().getReplicationFactor();
            HashMap<String, Integer> objectRepairCount = groupLedger.objectsThatNeedRepair(rf);
            logger.info("RF: {} - Objects needing repair: {}", rf, objectRepairCount);
            repair(objectRepairCount);
            busy.set(false);
        }
    }

    /**
     * Sends repairObject requests to the peer client future stub
     *
     * @param objects map of objects and number of repairs required
     * @return true if all repairs were successful, false otherwise
     */
    protected boolean repair(HashMap<String, Integer> objects) {
        ArrayList<String> groupStoragePeerList = new ObjectArrayList<>(groupStorageServer_peerServer.keySet());
        Multimap<PeerClient, String> peersObjectsRepairMap = HashMultimap.create();

        if (!objects.isEmpty() && !groupStoragePeerList.isEmpty()) {
            objects.forEach((objectId, count) -> {
                ArrayList<String> narrowed = groupLedger.removePeersStoringObject(groupStoragePeerList, objectId);
                ArrayList<String> randomPicks = pickNRandom(narrowed, count);
                if (!randomPicks.isEmpty()) {
                    randomPicks.forEach(peer -> {
                        PeerClient client = clients.get(groupStorageServer_peerServer.get(peer));
                        if (client != null) {
                            peersObjectsRepairMap.put(client, objectId);
                        }
                    });
                }
            });

            if (!peersObjectsRepairMap.isEmpty()) {
                logger.info("Object repair multimap: {}", peersObjectsRepairMap);
                return repair(peersObjectsRepairMap);
            }
        }
        return true;
    }

    /**
     * Sends repairObject requests to the peer client future stub
     *
     * @param peersObjectsRepairMap map of peers and keys that need to be repaired for that peer
     * @return true if all repairs were successful, false otherwise
     */
    protected boolean repair(Multimap<PeerClient, String> peersObjectsRepairMap) {
        ExecutorService executorService = Executors.newFixedThreadPool(3);
        ObjectOpenHashSet<Callable<Boolean>> callable = new ObjectOpenHashSet<>();
        boolean repairResult = true;

        peersObjectsRepairMap.asMap().forEach((client, objects) -> {
            callable.add(() -> client.repairObjectsFuture(objects));
        });

        try {
            List<Future<Boolean>> results = executorService.invokeAll(callable, 30, TimeUnit.SECONDS);
            executorService.shutdown();
            int successfulResults = 0;
            for (Future<Boolean> future : results) {
                if (!future.isCancelled()) {
                    if (future.isDone()) {
                        if (future.get()) {
                            successfulResults++;
                        } else {
                            repairResult = false;
                        }
                    } else {
                        if (future.get(2, TimeUnit.SECONDS)) {
                            successfulResults++;
                        } else {
                            repairResult = false;
                        }
                    }
                } else {
                    logger.warn("Cancelled!");
                }
            }

            logger.info("{} successful responses", successfulResults);
            return repairResult;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.info("Some puts failed!");
            executorService.shutdown();
            return false;
        }
    }

    protected ArrayList<String> pickNRandom(List<String> lst, int n) {
        ArrayList<String> copy = new ObjectArrayList<>(lst);
        Collections.shuffle(copy);
        return n > copy.size() ? copy.subList(0, copy.size()) : copy.subList(0, n);
    }

    public boolean migrate(String peerServer, String groupStorageServer, String newSuperPeerServer) {
        return true;
    }

    public boolean addObjectReference(String objectId, String peerId, long ttl) {
        MetaData.MetaDataBuilder metaDataBuilder = MetaData.builder().ttl(ttl);

        boolean objectLedgerResult = groupLedger.addToObjectLedger(objectId, metaDataBuilder.id(peerId).build());
        groupLedger.printObjectLedger();

        boolean peerLedgerResult = groupLedger.addToPeerLedger(peerId, metaDataBuilder.id(objectId).build());
        groupLedger.printPeerLedger();

        return objectLedgerResult && peerLedgerResult;
    }

    public boolean removePeerGroupLedger(String peerId) {
        groupLedger.removePeerFromGroupLedger(peerId);
        groupLedger.printObjectLedger();
        groupLedger.printPeerLedger();
        return true;
    }

    public void clearLedger() {
        groupLedger.clearAll();
    }

    public boolean pingPeer(String peerHostname) {
        return NetworkUtility.pingClient(peerHostname);
    }

    public boolean notifyPeers(String host) {
        logger.info("gRPC 'notifyPeers' request received");

        boolean result = true;
        boolean addPeerResult = true;
        if (!clients.isEmpty()) {
            for (PeerClient client : clients.values()) {
                logger.info("Sending notification to peer: {}", client.getClientIp());
                if (!host.equals(client.getClientIp())) {
                    addPeerResult = client.addGroupStoragePeer(host);
                }
                result = result && addPeerResult;
            }
        }
        return result;
    }

    public int getReplicationFactor() {
        return configuration.getStorage().getReplicationFactor();
    }

    public void handleShutdown() throws InterruptedException {
        if (!clients.isEmpty()) {
            if (clients.size() == 1) {
                clients.values().iterator().next().handleSuperPeerLeaveFuture();
                return;
            }

            List<HandleSuperPeerLeaveCallable> leaveCallables = new ArrayList<>();
            clients.values().forEach(client -> leaveCallables.add(new HandleSuperPeerLeaveCallable(client)));
            final ExecutorService taskExecutor = Executors.newFixedThreadPool(3);
            try {
                for (final Future<Boolean> future : taskExecutor.invokeAll(leaveCallables)) {
                    logger.debug("Leave response: {}", future.get());
                }
            } catch (ExecutionException | InterruptedException e) {
                logger.error("Error occurred whilst handling Super-peer leave requests");
            }

            try {
                taskExecutor.shutdown();
                taskExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Interrupted during shutdown of task executor");
                Thread.currentThread().interrupt();
            }
        } else {
            logger.debug("No peers found to notify...");
        }
    }

    public void takeLeadership(VirtualPosition virtualPosition) throws Exception {
        if (directoryServerClient != null) {
            logger.info("Initialising Super Peer server dependencies!");
            String superPeerHostname = superPeerIp + ":" + superPeerPort;
            directoryServerClient.setLeaderVoronoiSitePoint(superPeerIp + ":" + superPeerPort, virtualPosition.getX(), virtualPosition.getY());
            configuration.getNetworkHostnames().setSuperPeerServer(superPeerHostname);
        } else {
            logger.error("Directory server is not initialized");
            throw new IOException("Cannot initialize Super-Peer");
        }
        running = true;
    }

    public String getGroupName() {
        // May be redundant
        groupName = directoryServerClient.getGroupName();
        return groupName;
    }

    public void setGroupName(String groupName) {
        logger.info("Updating group name");
        this.groupName = groupName;
    }

    public int getSuperPeerPort() {
        return superPeerPort;
    }

    public boolean isRunning() {
        return running;
    }

    public void setGroupLedger(GroupLedger groupLedger) {
        logger.info("Updating group ledger");
        this.groupLedger = groupLedger;
    }

    public boolean migrationEnabled() {
        return configuration.getGroup().isMigration();
    }

    public void close() {
        logger.info("Shutting down Super-Peer components!");
        directoryServerClient.close();
        running = false;
    }
}
