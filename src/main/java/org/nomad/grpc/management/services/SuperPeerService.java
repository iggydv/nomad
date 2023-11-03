package org.nomad.grpc.management.services;

import io.grpc.stub.StreamObserver;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.grpc.GRPCUtils;
import org.nomad.grpc.superpeerservice.*;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.components.SuperPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Start gRPC Service
 * <p>
 * Only keeps record of PeerService IP:port
 */
@Component
public class SuperPeerService extends SuperPeerServiceGrpc.SuperPeerServiceImplBase {
    private final Logger logger = LoggerFactory.getLogger(SuperPeerService.class);
    private final GroupLedger groupLedger;
    private final SuperPeer superPeer;

    @Autowired
    public SuperPeerService(SuperPeer superPeer) {
        // Assume the storage unit has been initialized already
        this.groupLedger = GroupLedger.getInstance();
        this.superPeer = superPeer;
    }

    /**
     * Remove the PeerServer hostname
     *
     * @param peerServer hostname of the PeerServer to remove
     */
    public void removePeer(String peerServer, String groupStorageServer) {
        if (peerServer.isEmpty()) {
            return;
        }

        superPeer.handleLeave(peerServer, groupStorageServer);
    }

    /**
     * @param joinRequest      request containing the IP of the PeerServer that needs to be added to the clients map
     * @param responseObserver group ledger & join result
     */
    @Override
    public void handleJoin(JoinRequest joinRequest, StreamObserver<JoinResponseWithLedger> responseObserver) {
        logger.info("gRPC 'join' request received");
        AtomicBoolean accepted = new AtomicBoolean(false);
        String newPeer = joinRequest.getPeerServerHost();
        String newGroupStoragePeer = joinRequest.getGroupStorageServerHost();

        accepted.set(superPeer.handleJoin(newPeer, newGroupStoragePeer));

        JoinResponseWithLedger response = JoinResponseWithLedger.newBuilder()
                .setAccepted(accepted.get())
                .setObjectLedger(MultiMapPair.newBuilder().putAllKeyPair(groupLedger.getGrpcObjectLedger()).build())
                .setPeerLedger(MultiMapPair.newBuilder().putAllKeyPair(groupLedger.getGrpcPeerLedger()).build())
                .setReplicationFactor(superPeer.getReplicationFactor())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * @param leaveRequest     the hostname of the PeerServer to remove & the groupStorage hostname to notify peers to remove
     * @param responseObserver result of the remove
     */
    @Override
    public void handleLeave(LeaveRequest leaveRequest, StreamObserver<LeaveResponse> responseObserver) {
        logger.info("gRPC 'leave' request received");
        AtomicBoolean result = new AtomicBoolean(false);

        String groupStorageServer = leaveRequest.getGroupStorageServerHostname();
        String optionalPeerServer = leaveRequest.getPeerServerHostname();

        logger.debug("Group storage hostname to remove: {}", groupStorageServer);
        result.set(superPeer.handleLeave(optionalPeerServer, groupStorageServer));

        LeaveResponse response = LeaveResponse.newBuilder()
                .setSuccess(result.get())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void repair(RepairObjectRequest repairObjectRequest, StreamObserver<RepairObjectResponse> responseObserver) {
        logger.debug("gRPC 'repair' request received");
        AtomicBoolean result = new AtomicBoolean(false);
//        result.set(superPeer.repair(""));
        // String gameObjectGrpc = repairObjectRequest.getObjectId();
        // TODO: add logic
        RepairObjectResponse response = RepairObjectResponse.newBuilder()
                .setSucceed(true)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void migrate(MigrationRequest migrationRequest, StreamObserver<MigrationResponse> responseObserver) {
        logger.debug("gRPC 'migrate' request received");
        AtomicBoolean result = new AtomicBoolean(false);
        String superPeerId = migrationRequest.getNewSuperPeerId();
        String peerId = migrationRequest.getPeerId();
        result.set(superPeer.migrate(peerId, "", superPeerId));
        // TODO: add logic
        MigrationResponse response = MigrationResponse.newBuilder()
                .setSucceed(result.get())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void updatePositionReference(PositionUpdateRequest positionUpdateRequest, StreamObserver<PositionUpdateResponse> responseObserver) {
        logger.debug("gRPC 'updatePosition' request received");
        VirtualPosition newPosition = positionUpdateRequest.getPosition();
        PositionUpdateResponse.Builder partialResponse = PositionUpdateResponse.newBuilder();
        if (!superPeer.isWithinAOI(newPosition)) {
            logger.warn("Peer is now outside of AOI!");
            try {
                NeighbourData result = superPeer.getNeighbourSuperPeer(newPosition);
                String newSuperPeer = result.getLeaderData().getHostname();
                String newGroup = result.getGroupName();
                if (!newSuperPeer.isEmpty() && !newGroup.isEmpty()) {
                    partialResponse.setNewSuperPeer(newSuperPeer);
                    partialResponse.setNewGroupName(newGroup);
                }
            } catch (Exception exception) {
                logger.error("Couldn't retrieve neighbour leader data");
                exception.printStackTrace();
            }
        } else {
            logger.warn("Peer is still inside AOI!");
        }

        PositionUpdateResponse response = partialResponse.setAcknowledge(true).build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addObjectReference(AddObjectRequest addObjectRequest, StreamObserver<AddObjectResponse> responseObserver) {
        logger.debug("gRPC 'addObjectReference' request received");
        AtomicBoolean result = new AtomicBoolean(false);
        String objectId = addObjectRequest.getObjectId();
        String peerId = addObjectRequest.getPeerId();
        long ttl = addObjectRequest.getTtl();

        result.set(superPeer.addObjectReference(objectId, peerId, ttl));

        AddObjectResponse response = AddObjectResponse.newBuilder()
                .setSucceed(result.get())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void removePeerGroupLedger(RemovePeerGroupLedgerRequest addObjectRequest, StreamObserver<RemovePeerGroupLedgerResponse> responseObserver) {
        logger.info("gRPC 'removePeerGroupLedger' request received");
        AtomicBoolean result = new AtomicBoolean(false);
        String peerId = addObjectRequest.getPeerId();

        result.set(superPeer.removePeerGroupLedger(peerId));

        RemovePeerGroupLedgerResponse response = RemovePeerGroupLedgerResponse.newBuilder()
                .setResult(result.get())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void pingPeer(PingRequest pingRequest, StreamObserver<PingResponse> responseObserver) {
        String peerHostname = pingRequest.getHostname();
        logger.info("gRPC 'ping' request received for peer at {}", peerHostname);
        AtomicBoolean result = new AtomicBoolean(false);
        result.set(superPeer.pingPeer(peerHostname));

        PingResponse response = PingResponse.newBuilder()
                .setResult(result.get())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void notifyPeers(NotifyPeersRequest request, StreamObserver<NotifyPeersResponse> responseObserver) {
        logger.info("gRPC 'notifyPeers' request received");
        AtomicBoolean result = new AtomicBoolean(false);
        String host = request.getHost();

        result.set(superPeer.notifyPeers(host));

        NotifyPeersResponse response = NotifyPeersResponse.newBuilder()
                .setResult(result.get())
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    public void clearLedger() {
        superPeer.clearLedger();
    }
}
