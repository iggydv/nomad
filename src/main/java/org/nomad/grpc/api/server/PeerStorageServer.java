package org.nomad.grpc.api.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.nomad.commons.NetworkUtility;
import org.nomad.grpc.api.service.PeerStorageService;
import org.nomad.grpc.management.servers.GroupStorageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class PeerStorageServer {
    private final Logger logger = LoggerFactory.getLogger(GroupStorageServer.class);
    private final PeerStorageService peerStorageService;
    private int port;
    private Server server;
    private String host;

    @Autowired
    private PeerStorageServer(PeerStorageService groupStorageService) {
        this.peerStorageService = groupStorageService;
        NetworkUtility.init();
    }

    public void init(int port) {
        logger.info("Initializing group storage server...");
        this.port = port;
        host = NetworkUtility.getIP() + ":" + port;
        server = ServerBuilder.forPort(port).addService(peerStorageService).build();
    }

    /**
     * Start serving requests.
     */
    public void start() throws IOException {
        server.start();
        logger.info("Peer Storage Server started, listening on: {}", port);
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() throws InterruptedException {
        logger.info("Stopping Group Storage Server");
        if (server != null && (!server.isShutdown() || !server.isTerminated())) {
            logger.debug("gRPC server shutting down");
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
}
