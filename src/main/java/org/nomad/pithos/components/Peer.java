package org.nomad.pithos.components;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.delegation.DirectoryServerClient;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.nomad.grpc.management.clients.GroupStorageClient;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.models.JoinResponse;
import org.nomad.grpc.management.models.UpdatePeerPositionResponse;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.nomad.pithos.MainController;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.PeerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Second iteration of the peer, more in line with Pithos architecture
 * <p>
 * 1. receives all store, retrieve and modify requests from the higher application layer,
 * 2. forwards those requests to the required modules,
 * 3. forwards responses received from modules to the higher layer,
 * 4. keeps track of all outstanding requests (Section 5.3.3), and
 * 5. implements the quorum mechanism.
 */
@Component
@DependsOn("ZookeeperDirectoryServerClient")
public class Peer {
    private final Logger logger = LoggerFactory.getLogger(Peer.class);
    private final String peerIp;
    private final GenericGroupLedger groupLedger; // keep track of objects and peers
    private final DirectoryServerClient directoryServerClient;
    private final Config configuration;
    private final RetryPolicy<Object> superPeerRetryPolicy = new RetryPolicy<>()
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()))
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(3);
    private final RetryPolicy<Object> peerRetryPolicy = new RetryPolicy<>()
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()))
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(5);
    private final RetryPolicy<Void> voidRetryPolicy = new RetryPolicy<Void>()
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()))
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(3);
    @Resource(name = "peerStorage")
    private final PeerStorage peerStorage;
    private final Random rand = new Random();
    private final InputReader inputReader = new InputReader();
    private String id; // assigned by (SuperPeer) DS
    private String superPeerId; // retrieved from DS
    private int peerPort;
    private int groupStoragePort;
    private String peerGroupStorageHost;
    private String peerServer;
    private String group;
    private ArrayList<String> groupStoragePeerList; // populated by SuperPeer
    private SuperPeerClient superPeerClient;
    private VirtualPosition virtualPosition;
    private ArrayList<VirtualPosition> movements;
    private int lastPosition = 0;
    private boolean isActive = false;

    @Autowired
    public Peer(DirectoryServerClient directoryServerClient, PeerStorage peerStorage, Config configuration) {
        NetworkUtility.init();
        this.directoryServerClient = directoryServerClient;
        this.peerStorage = peerStorage;
        this.virtualPosition = VirtualPosition.newBuilder().setX(0).setY(0).setZ(0).build();
        this.peerIp = NetworkUtility.getIP();
        this.configuration = configuration;
        this.groupLedger = GroupLedger.getInstance();
    }

    private void start() {
        try {
            movements = inputReader.readMovements();
            logger.info("Initializing peer components");
            virtualPosition = movements.get(lastPosition);
            isActive = true;
            initializeStorage();
        } catch (Exception e) {
            logger.error("Unable to initialise storage components: {}", e.getMessage());
            isActive = false;
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public void setActive(boolean flag) {
        isActive = flag;
    }

    public boolean joinGroup(String group, String id, String superPeerId) throws Exception {
        logger.info("Joining {}...", group);
        this.group = group;
        this.id = id;
        this.superPeerId = superPeerId;

        createSuperPeerClient();
        if (!isActive) {
            start();
        }
        joinGroupAndUpdateLedger();
        updateGroupStorageComponents();
        return true;
    }

    /**
     * 1. Updates the peer & group server hostnames<p>
     * 2. Calls 'joinGroup' from super-peer client<p>
     * 3. Sets replication factor<p>
     * 4. Updates group ledger with response from super-peer<p>
     *
     * @throws IOException
     */
    protected void joinGroupAndUpdateLedger() throws IOException {
        JoinResponse joinResponse;
        peerGroupStorageHost = peerIp + ":" + groupStoragePort;
        peerServer = peerIp + ":" + peerPort;
        try {
            joinResponse = superPeerClient.joinGroup(peerServer, peerGroupStorageHost, virtualPosition);
        } catch (Exception e) {
            throw new IOException("Bootstrap to Super Peer failed");
        }
        // update the rf set by the super-peer
        configuration.getStorage().setReplicationFactor(superPeerClient.getSuperPeerReplicationFactor());
        groupLedger.populateGroupLedger(joinResponse.getObjectLedger(), joinResponse.getPeerLedger());
        groupLedger.printObjectLedger();
        groupLedger.printPeerLedger();
    }

    public void updateGroupStorageComponents() throws Exception {
        // group is updated when the group-member is created
        groupStoragePeerList = directoryServerClient.getGroupStorageHostnames(group);
        updateGroupStorageClients();
        //initializeGroupLedger();
        // Do an object repair for the first time
        // superPeerClient.repair()
        logger.info("Bootstrap Successful, Peer UUID -> {}", id);
        // THIS SHOULD BE THE GROUP STORAGE PORT
        // this could also be a routine check
        logger.debug("Notifying peers in group");
        superPeerClient.notifyPeers(peerGroupStorageHost);
    }

    public boolean assignNewRole() throws Exception {
        logger.info("Assigning new system role");
        boolean result;

        // TODO: might benefit from a debouncing mechanism
        getSuperPeerId();

        if (id.equals(superPeerId)) {
            logger.info("Peer {}, is now the Super-Peer", id);
            // TODO: remember to remove from group-ledger
            closeGroupStorageComponents();
            result = Failsafe.with(superPeerRetryPolicy).get(this::becomeSuperPeer);
        } else {
            logger.info("Peer {}, is re-joining group {}, with Super-Peer {}", id, group, superPeerId);
            result = Failsafe.with(peerRetryPolicy).get(() -> {
                createSuperPeerClient();
                return this.joinGroup(group, id, superPeerId);
            });
        }
        return result;
    }

    /**
     * Essentially acts as a health-check for peers within it's group
     * In the case that a peer health check fails or times out, it will be removed from the group storage clients list.
     *
     * @Schedule configured in application settings, currently running every 30 seconds
     */
    @Scheduled(fixedRateString = "${spring.schedule.healthCheck}")
    public void checkClient() {
        if (isActive && !groupStoragePeerList.isEmpty()) {
            logger.debug("Routine task: ping group storage client");
            executePings();
        }
    }

    private void executePings() {
        String randomGroupStorageClientHostname = groupStoragePeerList.get(rand.nextInt(groupStoragePeerList.size()));

        GroupStorageClient randomClient = peerStorage.getGroupStorage().getClient(randomGroupStorageClientHostname);
        if (randomClient != null) {
            if (randomClient.isActive()) {
                boolean pingResult = randomClient.pingClient();
                if (pingResult) {
                    logger.debug("Ping to client {} succeeded!", randomGroupStorageClientHostname);
                } else {
                    logger.error("Ping to client {} failed, escalating to Super peer!", randomGroupStorageClientHostname);
                    if (superPeerClient.isActive()) {
                        boolean superPeerResult = superPeerClient.pingPeer(randomGroupStorageClientHostname);
                        logger.info("Result from super peer is {}", superPeerResult);
                        if (superPeerResult) {
                            logger.error("Super peer ping succeeded - something is wrong with the peer-to-peer connection!");
                            peerStorage.getGroupStorage().checkClient(randomGroupStorageClientHostname);
                        } else {
                            logger.error("Super peer couldn't reach the Peer - removing from the group");
                            superPeerClient.leaveGroup(randomGroupStorageClientHostname);
                            // Could also call the removeGroupStoragePeer method here, but we want to ensure that all the group management
                            // is executed by the super peer to ensure consistency
                        }
                    }
                }
            }
        }
    }

    public boolean closeSuperPeerClientConnection() throws InterruptedException {
        logger.info("Closing Super Peer client connection");
        return superPeerClient.close();
    }

    public void getSuperPeerId() throws Exception {
        logger.debug("Getting super-peer Id");
        try {
            superPeerId = directoryServerClient.getGroupLeader(group);
        } catch (Exception e) {
            superPeerId = directoryServerClient.getGroupLeaderId(group);
        }

    }

    public boolean becomeSuperPeer() throws Exception {
        return MainController.becomeSuperPeer(virtualPosition);
    }

    private void createSuperPeerClient() throws Exception {
        // TODO check if you are not the super peer
        VoronoiSitePoint superPeerData = directoryServerClient.getLeaderData(group, superPeerId); // retrieve the IP of the leader
        String[] superPeerHostname = superPeerData.getHostname().split(":");
        String SuperPeerIp = superPeerHostname[0];
        int superPeerPort = Integer.parseInt(superPeerHostname[1]);
        String target = SuperPeerIp + ":" + superPeerPort;
        logger.debug("Super peer hostname: {}", target);

        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .maxInboundMessageSize(1024 * 1024 * 1024)
                .keepAliveTime(300, TimeUnit.SECONDS)
                .build();
        superPeerClient = new SuperPeerClient(channel);

        logger.debug("Updating group storage client reference!");
        peerStorage.updateGroupStorageSuperPeerClient(superPeerClient);
    }

    /**
     * MIGRATION
     * <p>
     * 1. Creates a new channel for super-peer client <p>
     * 2. Updates the group-storage component with the new super-peer client <p>
     *
     * @param hostname of the new super Peer
     */
    private void createSuperPeerClient(String hostname) {
        logger.debug("Super peer hostname: {}", hostname);
        ManagedChannel channel = ManagedChannelBuilder.forTarget(hostname).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
        superPeerClient = new SuperPeerClient(channel);
        logger.debug("Updating group storage client reference!");
        peerStorage.updateGroupStorageSuperPeerClient(superPeerClient);
    }

    /**
     * Initialize all storage components
     *
     * @throws Exception if storage fails to initialize
     */
    private void initializeStorage() throws Exception {
        groupStoragePort = NetworkUtility.randomPort(5001, 8999);
        logger.info("Initializing GroupStorage server with port: {}", groupStoragePort);

        String groupStorageHostname = peerIp + ":" + groupStoragePort;
        directoryServerClient.setGroupStorageHostname(groupStorageHostname);
        configuration.getNetworkHostnames().setGroupStorageServer(groupStorageHostname);
        // Unfortunately we need to pass the SP-client along to all the way down to the Group-Storage grpc service
        peerStorage.init(groupStoragePort, superPeerClient);
    }

    public boolean addGroupStoragePeer(String host) {
        logger.info("Adding peer: {}", host);
        boolean result;
        if (host.equals(peerGroupStorageHost)) {
            logger.warn("Not adding myself to group storage clients!");
            // this is required because during startup the peer receives group storage clients from the directory server
            groupStoragePeerList.remove(host);
            updateGroupStorageClients();
            return false;
        }

        if (groupStoragePeerList.contains(host)) {
            logger.info("Group Storage Peer already added: {}", host);
            return false;
        } else {
            result = groupStoragePeerList.add(host);
            updateGroupStorageClients();
            return result;
        }
    }

    public boolean removeGroupStoragePeer(String host) {
        logger.info("Removing peer: {}", host);
        boolean result = groupStoragePeerList.remove(host);
        if (result) {
            logger.info("Peer {} removed!", host);
        }
        updateGroupStorageClients();
        return result;
    }

    public boolean repairObjects(ArrayList<String> objects) {
        for (String objectId : objects) {
            try {
                // only check within the group, for repairs one does not want to check overlay
                GameObject objectToBeRepaired = peerStorage.get(objectId, true, false);
                if (objectToBeRepaired != null) {
                    try {
                        peerStorage.localPut(objectToBeRepaired);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("Interrupted");
                        return false;
                    }
                }
            } catch (NoSuchElementException | InterruptedException e) {
                logger.info("Object {} could not be repaired!", objectId);
            }
        }

        return true;
    }

    private void updateGroupStorageClients() {
        // initial update of clients
        peerStorage.updateGroupStorageClients(groupStoragePeerList);
    }

    public void removePeerFromGroupLedger() {
        String latestGroupStorageHostname = configuration.getNetworkHostnames().getGroupStorageServer();
        groupLedger.removePeerFromGroupLedger(latestGroupStorageHostname);
    }

    public void close() throws SQLException, InterruptedException {
        if (isActive) {
            logger.info("Closing Connection");
            String latestGroupStorageHostname = configuration.getNetworkHostnames().getGroupStorageServer();
            String latestPeerHostname = configuration.getNetworkHostnames().getPeerServer();
            logger.info("latestHostname: {}, set group hostame was: {}", latestPeerHostname, peerGroupStorageHost);
            // leaveGroup should be called on the hostname of the group storage client
            superPeerClient.leaveGroup(latestPeerHostname, latestGroupStorageHostname);
            groupLedger.removePeerFromGroupLedger(latestGroupStorageHostname);
            peerStorage.close();
            directoryServerClient.close();
            superPeerClient.close();
            isActive = false;
        }
    }

    /**
     * MIGRATION
     * <p>
     * 1. Notifies peers & super-peer to remove from ledger <p>
     * 2. Notifies super peer tp remove from group-storage & also triggers a group repair <p>
     * 3. Clear this peers ledger <p>
     *
     * @throws InterruptedException
     */
    private void closeForMigration() throws InterruptedException {
        String latestGroupStorageHostname = configuration.getNetworkHostnames().getGroupStorageServer();
        String latestPeerHostname = configuration.getNetworkHostnames().getPeerServer();
        peerStorage.notifyAllPeersRemovePeerFromGroupLedger();
        superPeerClient.leaveGroup(latestPeerHostname, latestGroupStorageHostname);
        groupLedger.clearAll();
        peerStorage.truncateAuthoritativeObjectStore();
    }

    private void closeGroupStorageComponents() throws Exception {
        logger.info("Closing Peer Storage components");
        peerStorage.close();
        directoryServerClient.setGroupStorageHostname("");
        directoryServerClient.setPeerHostname("");
        directoryServerClient.setLeaderHostname("");
        logger.info("Peer Storage components closed");
    }

    /*
     * ===================================================================================
     * Getters & Setter
     * ====================================================================================
     */

    public void setPeerPort(int peerPort) {
        this.peerPort = peerPort;
    }

    /*
     * ===================================================================================
     * Start Storage
     * ====================================================================================
     */

    @Scheduled(fixedRateString = "${spring.schedule.updatePosition}", initialDelay = 60000)
    public void updatePeerPosition() {
        if (!isActive) {
            return;
        }

        if (configuration.getGroup().isMigration()) {
            if (lastPosition >= movements.size() - 1) {
                lastPosition = 0;
                Collections.reverse(movements);
            }
            virtualPosition = movements.get(lastPosition);
            lastPosition++;
            logger.info("Position: {}", virtualPosition);
            UpdatePeerPositionResponse result;
            try {
                result = superPeerClient.updatePeerPositionFuture(peerServer, virtualPosition);
                if (result.isAck()) {
                    logger.debug("Acknowledge received");
                    String newSuperPeer = result.getNewSuperPeer();
                    String newGroup = result.getNewGroup();
                    if (!newSuperPeer.isEmpty() && !newGroup.isEmpty()) {
                        logger.info("Migration of peer initiated for peer: {} in {} to {} (sp: {})", peerServer, group, newGroup, newSuperPeer);
                        try {
                            migratePeer(newSuperPeer, newGroup);
                        } catch (Exception exception) {
                            logger.error("Migration failed");
                            exception.printStackTrace();
                        }
                    }
                }
            } catch (InterruptedException e) {
                // e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void migratePeer(String superPeerIP, String groupName) throws Exception {
        closeForMigration();
        group = groupName;
        superPeerId = directoryServerClient.getGroupLeaderId(groupName);
        createSuperPeerClient(superPeerIP);
        directoryServerClient.migrateMember(groupName);
        Failsafe.with(voidRetryPolicy).run(this::joinGroupAndUpdateLedger);
        configuration.getNetworkHostnames().setSuperPeerServer(superPeerIP);
    }

    private int randomNumber(int absoluteBound) {
        Random random = new Random();
        return random.nextInt(absoluteBound) * (random.nextBoolean() ? -1 : 1);
    }
}
