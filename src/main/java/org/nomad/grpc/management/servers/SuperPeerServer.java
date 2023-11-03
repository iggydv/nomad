package org.nomad.grpc.management.servers;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.nomad.grpc.groupstorage.HealthCheckResponse;
import org.nomad.grpc.management.services.SuperPeerService;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.nomad.pithos.components.SuperPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.NOT_SERVING;
import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.SERVING;

@Service
public class SuperPeerServer {
    static private HealthCheckResponse.ServingStatus status = NOT_SERVING;
    private final Logger logger = LoggerFactory.getLogger(SuperPeerServer.class);
    private final int port;
    private final Server server;
    private final SuperPeer superPeer;
    private final SuperPeerService superPeerService;
    private final String group;

    @Autowired
    public SuperPeerServer(SuperPeer superPeer, SuperPeerService superPeerService) {
        // will become static port once using the internet
        this.port = superPeer.getSuperPeerPort();
        this.group = superPeer.getGroupName();
        this.superPeer = superPeer;

        // TODO might need to move to the start method
        this.superPeerService = superPeerService;
        ServerBuilder<?> serverBuilder = ServerBuilder.forPort(port);
        this.server = serverBuilder.addService(superPeerService).build();
    }

    /**
     * Initialize the peerServer, a gRPC server for peer commands
     *
     * @throws IOException if either the peer service or peer object fails to start
     */
    public void start() throws Exception {
        server.start();
        logger.info("Super Peer Server started, listening on: {}", port);
        status = SERVING;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                SuperPeerServer.this.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                e.printStackTrace(System.err);
            }
        }));
    }

    public void takeLeadership(VirtualPosition virtualPosition) throws Exception {
        superPeer.takeLeadership(virtualPosition);
    }

    public void removePeer(String peerServer, String groupStorageServer) {
        superPeer.handleLeave(peerServer, groupStorageServer);
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() throws InterruptedException {
        logger.info("Stopping super peer server");
        superPeer.handleShutdown();
        superPeer.close();
        if (server != null) {
            status = NOT_SERVING;
            logger.debug("gRPC server shutting down");
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    public String getHealthStatus() {
        return status.toString();
    }
}
