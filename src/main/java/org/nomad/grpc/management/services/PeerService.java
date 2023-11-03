package org.nomad.grpc.management.services;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.nomad.grpc.GRPCUtils;
import org.nomad.grpc.peerservice.AddPeerRequest;
import org.nomad.grpc.peerservice.AddPeerResponse;
import org.nomad.grpc.peerservice.PeerServiceGrpc;
import org.nomad.grpc.peerservice.RemovePeerRequest;
import org.nomad.grpc.peerservice.RemovePeerResponse;
import org.nomad.grpc.peerservice.RepairObjectRequest;
import org.nomad.grpc.peerservice.RepairObjectResponse;
import org.nomad.grpc.peerservice.SuperPeerLeaveResponse;
import org.nomad.pithos.components.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Start gRPC Service
 */
@Component
public class PeerService extends PeerServiceGrpc.PeerServiceImplBase {

    private final Logger logger = LoggerFactory.getLogger(PeerService.class);
    private final Peer peer;
    private final AtomicBoolean handlingSuperPeerLeave = new AtomicBoolean(false);
    private final RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
            .withDelay(Duration.ofSeconds(1))
            .handle(Exception.class)
            .withMaxRetries(3);

    @Autowired
    public PeerService(Peer peer) {
        this.peer = peer;
    }

    @Override
    public void addPeer(AddPeerRequest addPeerRequest, StreamObserver<AddPeerResponse> responseObserver) {
        logger.info("gRPC 'addPeer' request received");
        String host = addPeerRequest.getHost();
        boolean result = peer.addGroupStoragePeer(host);
        // groupLedger.addToObjectLedger(objectId, peerId); TODO add boolean response to addition?
        // groupLedger.addToPeerLedger(objectId);
        AddPeerResponse response = AddPeerResponse.newBuilder()
                .setResponse(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void removePeer(RemovePeerRequest removePeerRequest, StreamObserver<RemovePeerResponse> responseObserver) {
        logger.info("gRPC 'removePeer' request received");
        String host = removePeerRequest.getHost();
        boolean result = peer.removeGroupStoragePeer(host);
        // groupLedger.addToObjectLedger(objectId, peerId);
        // groupLedger.addToPeerLedger(objectId);
        RemovePeerResponse response = RemovePeerResponse.newBuilder().setResponse(result).build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void repairObjects(RepairObjectRequest repairObjectRequest, StreamObserver<RepairObjectResponse> responseObserver) {
        logger.info("gRPC 'repairObjects' request received");
        ObjectList<String> objectsToRepair = new ObjectArrayList<>(repairObjectRequest.getObjectIdsList());
        boolean result = peer.repairObjects(objectsToRepair);

        RepairObjectResponse response = RepairObjectResponse.newBuilder().setResponse(result).build();
        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void handleSuperPeerLeave(Empty request, StreamObserver<SuperPeerLeaveResponse> responseObserver) {
        logger.info("gRPC 'superPeerLeave' request received");
        boolean result = false;

        if (!handlingSuperPeerLeave.get()) {
            handlingSuperPeerLeave.set(true);
            try {
                result = peer.closeSuperPeerClientConnection();
                Failsafe.with(retryPolicy).get(peer::assignNewRole);
                logger.info("New Role assigned successfully, continuing services ...");
            } catch (Exception e) {
                logger.error("Failed to assign new role!", e);
                System.exit(-1);
            } finally {
                handlingSuperPeerLeave.set(false);
            }
        } else {
            logger.error("gRPC 'superPeerLeave' Request already received!");
        }

        SuperPeerLeaveResponse response = SuperPeerLeaveResponse.newBuilder()
                .setResponse(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
