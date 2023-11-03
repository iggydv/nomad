package org.nomad.grpc.management.servers;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.nomad.config.Config;
import org.nomad.grpc.groupstorage.HealthCheckResponse;
import org.nomad.grpc.management.services.PeerService;
import org.nomad.pithos.components.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.NOT_SERVING;
import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.SERVING;

@Service
public class PeerServer {
    static private HealthCheckResponse.ServingStatus status = NOT_SERVING;
    private final Logger logger = LoggerFactory.getLogger(PeerServer.class);
    private final Peer peer;
    private final PeerService peerService;
    private final Config config;
    private int port;
    private boolean initialized = false;
    private Server server;

    @Autowired
    public PeerServer(Peer peer, PeerService peerService, Config config) {
        this.peer = peer;
        this.peerService = peerService;
        this.config = config;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void init(int port) {
        logger.info("Initializing peer server");
        this.initialized = true;
        this.port = port;
        this.peer.setPeerPort(port);
        this.server = ServerBuilder.forPort(port).addService(peerService).build();
    }

    public void start() throws Exception {
        if (!initialized) {
            throw new IOException("Not initialized: Please initialize before starting the peer server");
        }
        server.start();
        status = SERVING;

        logger.info("Peer Server started, listening on: {}", port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (initialized) {
                try {
                    peer.close();
                    PeerServer.this.stop();
                } catch (InterruptedException | SQLException e) {
                    e.printStackTrace(System.err);
                }
                initialized = false;
            }
        }));
    }

    public boolean joinGroup(String group, String id, String superPeerId) throws Exception {
        if (!initialized) {
            throw new IOException("Not initialized: Please initialize before starting the peer server");
        }
        return peer.joinGroup(group, id, superPeerId);
    }

    public String getServerStatus() {
        return status.toString();
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() throws InterruptedException, SQLException {
        logger.info("Stopping peer server (if active)");
        if (server != null && initialized) {
            peer.setActive(false);
            peer.removePeerFromGroupLedger();
            initialized = false;
            status = NOT_SERVING;
            assert server != null;
            peer.close();
            logger.debug("gRPC server shutting down");
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

}
