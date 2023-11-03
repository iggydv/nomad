package org.nomad.grpc.management.servers;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.Data;
import org.nomad.commons.NetworkUtility;
import org.nomad.grpc.management.clients.GroupStorageClient;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.services.GroupStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.NOT_SERVING;
import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.SERVING;

@Data
@Service
public class GroupStorageServer {
    private final Logger logger = LoggerFactory.getLogger(GroupStorageServer.class);

    private int port;
    private Server server;
    private GroupStorageService groupStorageService;
    private String host;
    private boolean initialized = false;

    @Autowired
    private GroupStorageServer(GroupStorageService groupStorageService) {
        this.groupStorageService = groupStorageService;
        NetworkUtility.init();
    }

    public void init(int port, SuperPeerClient superPeerClient) {
        logger.info("Initializing group storage server...");
        this.port = port;
        initialized = true;
        host = NetworkUtility.getIP() + ":" + port;
        groupStorageService.updateSuperPeerClient(superPeerClient);
        groupStorageService.updateHostname(host);
        server = ServerBuilder.forPort(port).addService(groupStorageService).build();
    }

    /**
     * Start serving requests.
     */
    public void start() throws IOException {
        server.start();
        logger.info("Group Storage Server started, listening on: {}", port);
        groupStorageService.setStatus(SERVING);
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() throws InterruptedException {
        logger.info("Stopping Group Storage Server");
        if (server != null) {
            groupStorageService.setStatus(NOT_SERVING);
            logger.debug("gRPC server shutting down");
            server.shutdownNow();
        }
    }

    public void updateSuperPeerClient(SuperPeerClient superPeerClient) {
        logger.info("Updating super-peer client");
        groupStorageService.updateSuperPeerClient(superPeerClient);
    }

    public void updateAvailableClients(List<GroupStorageClient> clients) {
        logger.debug("Updating clients for gRPC server reference");
        groupStorageService.updateGroupStorageClients(clients);
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
