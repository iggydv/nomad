package org.nomad.pithos;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.nomad.application.AppContainerCustomizer;
import org.nomad.commons.NetworkUtility;
import org.nomad.config.Config;
import org.nomad.delegation.DirectoryServerClient;
import org.nomad.delegation.models.GroupData;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.grpc.api.server.PeerStorageServer;
import org.nomad.grpc.management.servers.PeerServer;
import org.nomad.grpc.management.servers.SuperPeerServer;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.nomad.pithos.models.ComponentType;
import org.nomad.storage.overlay.DHTOverlayStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.Scanner;

import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.SERVING;
import static org.nomad.pithos.models.ComponentType.NONE;
import static org.nomad.pithos.models.ComponentType.SUPER_PEER;

@Service
public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    private static final RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(3)
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()));

    private static final RetryPolicy<GroupData> joinRetryPolicy = new RetryPolicy<GroupData>()
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(3)
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()));

    private static final RetryPolicy<Object> generalRetryPolicy = new RetryPolicy<>()
            .withBackoff(1, 30, ChronoUnit.SECONDS)
            .handle(Exception.class)
            .withMaxRetries(3)
            .onRetry(e -> logger.warn("Failure #{}. Retrying.", e.getAttemptCount()));

    private static ComponentType role;
    private static PeerServer peerServer;
    private static SuperPeerServer superPeerServer;
    private static PeerStorageServer peerStorageServer;
    private static DHTOverlayStorage overlayStorage;
    private static DirectoryServerClient directoryServerClient;
    private static AppContainerCustomizer appContainerCustomizer;
    private static Config configuration;
    private static ScheduledLedgerCleanUp ledgerCleanUp;
    private static VirtualPosition virtualPosition;
    private static String id;
    private static String superPeerId;
    private static String group;
    private static String ip;

    @Autowired
    public MainController(SuperPeerServer superPeerServer, PeerServer peerServer, PeerStorageServer peerStorageServer,
                          DHTOverlayStorage overlayStorage, DirectoryServerClient directoryServerClient,
                          AppContainerCustomizer appContainerCustomizer, Config configuration) {
        MainController.superPeerServer = superPeerServer;
        MainController.peerServer = peerServer;
        MainController.peerStorageServer = peerStorageServer;
        MainController.overlayStorage = overlayStorage;
        MainController.directoryServerClient = directoryServerClient;
        MainController.appContainerCustomizer = appContainerCustomizer;
        MainController.configuration = configuration;
        initFixedSuperPeerVirtualPosition();
    }

    private static ComponentType chooseRole() throws Exception {
        // start the super-peer server (we want super-peer redundancy)
        superPeerServer.start();

        if (id.equals(superPeerId)) {
            // become a Super-Peer in the network
            boolean result = Failsafe.with(retryPolicy)
                    .onFailure(e -> logger.error("Failed to become super-peer"))
                    .onSuccess(e -> logger.info("Successfully became super-peer"))
                    .get(() -> MainController.becomeSuperPeer(virtualPosition));
            if (!result) {
                logger.error("Failed to become Super-Peer!");
                return NONE;
            }
            logger.error(configuration.getNetworkHostnames().toString());
            logger.info(directoryServerClient.getDataMap().toString());
            return SUPER_PEER;
        }

        // become a Peer in the network
        initializePeerServer();
        peerServer.start();
        peerStorageServer.start();

        boolean joinResult = Failsafe.with(retryPolicy)
                .onFailure(e -> logger.error("failed to get group leader"))
                .onSuccess(e -> logger.info("joined group successfully"))
                .get(() -> peerServer.joinGroup(group, id, superPeerId));

        if (joinResult) {
            logger.error(configuration.getNetworkHostnames().toString());
            logger.info(directoryServerClient.getDataMap().toString());
            logger.info("Peer Status: {}", peerServer.getServerStatus());
            logger.error("http://{}:{}/{}/swagger-ui/index.html", ip, appContainerCustomizer.getTomcatPort(), "nomad");

            return ComponentType.PEER;
        }
        return NONE;
    }

    public static void stopPeerServer() throws InterruptedException, SQLException {
        if (peerServer.getServerStatus().equals("SERVING")) {
            peerServer.stop();
        }
    }

    /**
     * 1. Set peer-server as non-active, removes this peers group-storage hostname from the group-ledger
     * 2. Stops the peer-server (if it's active)
     * 3. Sets the config & DS LEADER data
     * 4. Removes it's own peer-server hostname from the super-peer clients list & notify peers
     * 5. Resets the peer & group-storage config
     *
     * @return
     * @throws Exception
     */
    public static boolean becomeSuperPeer(VirtualPosition virtualPosition) {
        logger.debug("Becoming group leader...");
        if (peerServer.isInitialized()) {
            try {
                peerServer.stop();
                peerStorageServer.stop();
            } catch (InterruptedException | SQLException e) {
                logger.error("Could not stop peer-server!");
                e.printStackTrace();
            }
        }

        logger.debug("Successfully stopped peer services");
        role = SUPER_PEER;
        try {
            superPeerServer.takeLeadership(virtualPosition);
        } catch (Exception exception) {
            logger.error("Could not take leadership!");
            exception.printStackTrace();
        }
        superPeerServer.removePeer(configuration.getNetworkHostnames().getPeerServer(), configuration.getNetworkHostnames().getGroupStorageServer());
        // finally we no longer need this
        configuration.getNetworkHostnames().setPeerServer("");
        configuration.getNetworkHostnames().setPeerStorageServer("");
        configuration.getNetworkHostnames().setGroupStorageServer("");
        logger.info("Super Peer status: {}", superPeerServer.getHealthStatus());
        logger.info("Virtual Position: x= {}, y={}", virtualPosition.getX(), virtualPosition.getY());
        return superPeerServer.getHealthStatus().equals(SERVING.name());
    }

    private static void initializePeerServer() {
        int peerPort = NetworkUtility.randomPort(5001, 8999);
        int peerStorageApiPort = NetworkUtility.randomPort(5001, 8999);
        String peerIp = NetworkUtility.getIP();
        String peerHostname = peerIp + ":" + peerPort;
        String peerStorageHostname = peerIp + ":" + peerStorageApiPort;
        peerServer.init(peerPort);
        peerStorageServer.init(peerStorageApiPort);
        try {
            directoryServerClient.setPeerHostname(peerHostname);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        configuration.getNetworkHostnames().setPeerServer(peerHostname);
        configuration.getNetworkHostnames().setPeerStorageServer(peerStorageHostname);
    }

    private static void initFixedSuperPeerVirtualPosition() {
        Random random = new Random();
        virtualPosition = VirtualPosition.newBuilder()
                .setX(rounded(random.nextDouble() * configuration.getWorld().getWidth()))
                .setY(rounded(random.nextDouble() * configuration.getWorld().getWidth()))
                .setZ(0)
                .build();
    }

    private static double rounded(double number) {
        return Math.round(number * 1000.0) / 1000.0;
    }

    public void start() {
        start(NONE);
//        logger.info("Initializing Nomad components");
//
//        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
//
//        ledgerCleanUp = new ScheduledLedgerCleanUp();
//        ledgerCleanUp.startAsync();
//        // Initialize the port utility, Important for selecting the IP and binding interface (which is not currently used)
//        NetworkUtility.init();
//        ip = NetworkUtility.getIP();
//        GroupData joinResult;
//
//        try {
//            Failsafe.with(retryPolicy)
//                    .onFailure(e -> logger.info("failed to init directory server, retrying"))
//                    .onComplete(e -> logger.info("initialized successfully"))
//                    .run(() -> directoryServerClient.init(configuration.getWorld().getWidth(), configuration.getWorld().getHeight()));
//
//            joinResult = Failsafe.with(joinRetryPolicy)
//                    .onFailure(e -> logger.error("join failed"))
//                    .onSuccess(e -> logger.info("join successful"))
//                    .get(() -> directoryServerClient.joinGroup(virtualPosition));
//
//            group = joinResult.getGroupName();
//            id = joinResult.getPeerId();
//            NetworkUtility.setID(id);
//
//            superPeerId = Failsafe.with(generalRetryPolicy)
//                    .onFailure(e -> logger.error("failed to get group leader"))
//                    .onComplete(e -> logger.info("group leader saved"))
//                    .get(() -> directoryServerClient.getGroupLeader(group));
//
//            logger.info("Bootstrap Successful!");
//            logger.info("Peer ID assigned -> {}", id);
//            logger.info("Group joined -> {}", group);
//            logger.info("SuperPeer ID -> {}", superPeerId);
//
//            int overlayPort = Failsafe.with(generalRetryPolicy)
//                    .onFailure(e -> logger.error("failed to get group leader"))
//                    .onComplete(e -> logger.info("joined overlay"))
//                    .get(this::initOverlayDHT);
//
//            String overlayHostname = ip + ":" + overlayPort;
//            directoryServerClient.setDHTHostname(overlayHostname);
//            configuration.getNetworkHostnames().setOverlayServer(overlayHostname);
//
//            logger.info("Node with ID '{}' is the Super Peer", superPeerId);
//            role = chooseRole();
//
//        } catch (IllegalStateException e) {
//            logger.error("Unable to initialise storage components");
//            // e.printStackTrace();
//            System.exit(1);
//        } catch (Exception e) {
//            logger.error("Something went wrong - Bailing");
//            // e.printStackTrace();
//            System.exit(2);
//        }
    }

    public void start(ComponentType type) {
        logger.info("Initializing Nomad components");

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));

        ledgerCleanUp = new ScheduledLedgerCleanUp();
        ledgerCleanUp.startAsync();
        // Initialize the port utility, Important for selecting the IP and binding interface (which is not currently used)
        NetworkUtility.init();
        ip = NetworkUtility.getIP();
        GroupData joinResult;

        try {
            Failsafe.with(retryPolicy)
                    .onFailure(e -> logger.info("failed to init directory server, retrying"))
                    .onComplete(e -> logger.info("initialized successfully"))
                    .run(() -> directoryServerClient.init(configuration.getWorld().getWidth(), configuration.getWorld().getHeight()));

            switch (type) {
                case SUPER_PEER: {
                    joinResult = Failsafe.with(joinRetryPolicy)
                            .onFailure(e -> logger.error("join failed"))
                            .onSuccess(e -> logger.info("join successful"))
                            .get(() -> directoryServerClient.newGroupLeader(virtualPosition));
                    break;
                }
                case NONE:
                case PEER:
                default: {
                    // Default behaviour
                    joinResult = Failsafe.with(joinRetryPolicy)
                            .onFailure(e -> logger.error("join failed"))
                            .onSuccess(e -> logger.info("join successful"))
                            .get(() -> directoryServerClient.joinGroup(virtualPosition));
                    break;
                }
            }

            group = joinResult.getGroupName();
            id = joinResult.getPeerId();
            NetworkUtility.setID(id);

            superPeerId = Failsafe.with(generalRetryPolicy)
                    .onFailure(e -> logger.error("failed to get group leader"))
                    .onComplete(e -> logger.info("group leader saved"))
                    .get(x -> directoryServerClient.getGroupLeader(group));

            logger.info("Bootstrap Successful!");
            logger.info("Peer ID assigned -> {}", id);
            logger.info("Group joined -> {}", group);
            logger.info("SuperPeer ID -> {}", superPeerId);

            int overlayPort = Failsafe.with(generalRetryPolicy)
                    .onFailure(e -> logger.error("failed to get group leader"))
                    .onComplete(e -> logger.info("joined overlay"))
                    .get(this::initOverlayDHT);

            String overlayHostname = ip + ":" + overlayPort;
            directoryServerClient.setDHTHostname(overlayHostname);
            configuration.getNetworkHostnames().setOverlayServer(overlayHostname);

            logger.info("Node with ID '{}' is the Super Peer", superPeerId);
            role = chooseRole();

        } catch (IllegalStateException e) {
            logger.error("Unable to initialise storage components");
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            logger.error("Something went wrong - Bailing");
            e.printStackTrace();
            System.exit(2);
        }
    }

    private int initOverlayDHT() throws Exception {
        // Ensure group name is updated
        group = directoryServerClient.getGroupName();
        logger.debug("Latest group name from the Directory Server: {}", group);
        String dhtBootstrapHost = getDhtBootstrapHost(group);
        if (!dhtBootstrapHost.isEmpty()) {
            return overlayStorage.joinOverlay(dhtBootstrapHost);
        } else {
            // If Voronoi grouping is enabled, get the voronoi neighbour, else just get the next sequential group.
            String neighbourGroupName = configuration.getGroup().isVoronoiGrouping() ? getVoronoiNeighbourGroupName() : getNeighbourGroupName();
            logger.info("DHT bootstrap host for {} is empty, trying {}...", group, neighbourGroupName);
            dhtBootstrapHost = Failsafe.with(generalRetryPolicy)
                    .onFailure(e -> logger.error("failed to get bootstrap peer"))
                    .get(() -> getDhtBootstrapHost(neighbourGroupName));

            if (!dhtBootstrapHost.isEmpty()) {
                return overlayStorage.joinOverlay(dhtBootstrapHost);
            }

            if (initialiseNewOverlay()) {
                logger.warn("Initializing Overlay Storage for the first time");
                return overlayStorage.initOverlay();
            } else {
                logger.warn("Initializing Overlay Storage for the first time");
                throw new IllegalStateException("Not initializing overlay!");
            }
        }
    }

    private String getDhtBootstrapHost(String group) throws Exception {
        return MainController.group.equals(group) ? directoryServerClient.getDHTHostnamesFromGroupMember(group) :
                directoryServerClient.getGroupMemberDHTHostname(group);
    }

    private String getNeighbourGroupName() {
        int currentGroupNumber = Integer.parseInt(group.split("-")[1]);
        int number = currentGroupNumber > 1 ? --currentGroupNumber : currentGroupNumber;
        return "group-" + number;
    }

    private String getVoronoiNeighbourGroupName() {
        try {
            if (group.equals("group-1")) {
                return getNeighbourGroupName();
            }
            NeighbourData data = directoryServerClient.getNeighbouringLeaderData(virtualPosition);
            return data.getGroupName();
        } catch (Exception e) {
            e.printStackTrace();
            return getNeighbourGroupName();
        }
    }

    public void checkLeader() throws Exception {
        assert id.equals(directoryServerClient.getGroupLeader(group));
    }

    public void close() {
        try {
            ledgerCleanUp.stopAsync();
            directoryServerClient.close();
            peerServer.stop();
            peerStorageServer.stop();
            superPeerServer.stop();
        } catch (InterruptedException | SQLException e) {
            logger.warn("Interrupted while closing components");
        }
    }

    private boolean initialiseNewOverlay() {
        Scanner kbd = new Scanner(System.in);
        String decision;
        System.out.println("Do you wish to initialise a new overlay? (y/n)");
        decision = kbd.nextLine();

        switch (decision) {
            case "yes":
            case "y":
                return true;

            case "no":
            case "n":
            default:
                return false;
        }
    }
}
