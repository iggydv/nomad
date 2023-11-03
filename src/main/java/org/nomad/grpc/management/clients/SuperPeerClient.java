package org.nomad.grpc.management.clients;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.nomad.grpc.management.models.JoinResponse;
import org.nomad.grpc.management.models.UpdatePeerPositionResponse;
import org.nomad.grpc.superpeerservice.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SuperPeerClient {
    private static final Logger logger = LoggerFactory.getLogger(SuperPeerClient.class);

    private final SuperPeerServiceGrpc.SuperPeerServiceBlockingStub blockingStub;
    private final SuperPeerServiceGrpc.SuperPeerServiceFutureStub futureStub;
    private final ManagedChannel managedChannel;
    private boolean isActive;
    private int superPeerReplicationFactor = 1; // default

    public SuperPeerClient(Channel channel) {
        this.managedChannel = (ManagedChannel) channel;
        this.blockingStub = SuperPeerServiceGrpc.newBlockingStub(channel);
        this.futureStub = SuperPeerServiceGrpc.newFutureStub(channel);
        this.isActive = true;
    }

    public boolean close() throws InterruptedException {
        isActive = false;
        return managedChannel.shutdown().awaitTermination(350, TimeUnit.SECONDS);
    }

    public boolean isActive() {
        return isActive;
    }

    /**
     * Send GRPC join request to the Super Peer (30 sec timeout)
     * On response, the client updates the group ledger
     *
     * @param peerServerHostname         of the Peer Server to add
     * @param groupStorageServerHostname of the Group Storage Server to add
     */
    public JoinResponse joinGroup(String peerServerHostname, String groupStorageServerHostname, VirtualPosition position) throws IOException {
        logger.info("Sending join request for peer: {} to super-peer: {}", peerServerHostname, managedChannel.authority());

        JoinRequest request = JoinRequest.newBuilder()
                .setPeerServerHost(peerServerHostname)
                .setGroupStorageServerHost(groupStorageServerHostname)
                .setPosition(position)
                .build();

        JoinResponseWithLedger response = blockingStub.handleJoin(request);

        if (response.getAccepted()) {
            logger.debug("Accepted by Super Peer");
            logger.debug("Updating replication factor");
            superPeerReplicationFactor = response.getReplicationFactor();
            MultiMapPair objectLedger = response.getObjectLedger();
            MultiMapPair peerLedger = response.getPeerLedger();
            logger.debug("Populating group ledger");
            return JoinResponse.builder().objectLedger(objectLedger).peerLedger(peerLedger).build();
        } else {
            logger.error("Rejected by Super Peer");
            throw new IOException();
        }
    }

    public boolean leaveGroup(String peerServer, String groupStorageServer) {
        LeaveRequest request = LeaveRequest.newBuilder()
                .setPeerServerHostname(peerServer)
                .setGroupStorageServerHostname(groupStorageServer)
                .build();

        LeaveResponse response = blockingStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS).handleLeave(request);
        return response.getSuccess();
    }

    // Used for Ping leave (the peer client data might not be known)
    public boolean leaveGroup(String groupStorageServer) {
        LeaveRequest request = LeaveRequest.newBuilder()
                .setGroupStorageServerHostname(groupStorageServer)
                .build();

        LeaveResponse response = blockingStub.withDeadlineAfter(60, TimeUnit.SECONDS).handleLeave(request);
        return response.getSuccess();
    }

    public boolean notifyPeers(String host) {
        NotifyPeersRequest request = NotifyPeersRequest.newBuilder().setHost(host).build();
        NotifyPeersResponse response = blockingStub.withDeadlineAfter(60, TimeUnit.SECONDS).notifyPeers(request);
        return response.getResult();
    }

    public boolean pingPeer(String host) {
        PingRequest request = PingRequest.newBuilder().setHostname(host).build();
        PingResponse response = blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS).pingPeer(request);
        return response.getResult();
    }

    public boolean addObjectReference(String objectId, String peerId, long ttl) throws InterruptedException {
        logger.debug("Contacting Super-Peer: Adding object reference, {}:{}", objectId, peerId);
        if (!isActive) {
            logger.warn("client not active!");
            return false;
        }
        AddObjectRequest request = AddObjectRequest.newBuilder()
                .setObjectId(objectId)
                .setPeerId(peerId)
                .setTtl(ttl)
                .build();

        AddObjectResponse response = blockingStub
                .withDeadlineAfter(350, TimeUnit.MILLISECONDS)
                .addObjectReference(request);

        return response.getSucceed();
    }

    public boolean addObjectReferenceFuture(String objectId, String peerId, long ttl) throws InterruptedException {
        logger.debug("Contacting Super-Peer: Adding object reference, {}:{}", objectId, peerId);
        if (!isActive) {
            logger.warn("client not active!");
            return false;
        }
        AddObjectRequest request = AddObjectRequest.newBuilder()
                .setObjectId(objectId)
                .setPeerId(peerId)
                .setTtl(ttl)
                .build();

        ListenableFuture<AddObjectResponse> futureResponse = futureStub.addObjectReference(request);
        final CountDownLatch finishLatch = new CountDownLatch(1);

        Futures.addCallback(futureResponse, new FutureCallback<AddObjectResponse>() {
            @Override
            public void onSuccess(@Nullable AddObjectResponse result) {
                logger.debug("Successful request");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("AddObjectReference Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        AddObjectResponse addObjectResponse;
        if (!futureResponse.isCancelled()) {
            try {
                addObjectResponse = futureResponse.get();
                logger.debug("Successful AddObjectReference request");
                return addObjectResponse.getSucceed();
            } catch (ExecutionException e) {
                // e.printStackTrace();
                logger.error("AddObjectReference failed!");
                return false;
            }
        } else {
            logger.error("AddObjectReference has been cancelled!");
            return false;
        }
    }

    public boolean removePeerGroupLedger(String peerId) throws InterruptedException {
        logger.debug("Contacting Super-Peer: removing peer, {}", peerId);
        if (!isActive) {
            logger.warn("client not active!");
            return false;
        }
        RemovePeerGroupLedgerRequest request = RemovePeerGroupLedgerRequest.newBuilder().setPeerId(peerId).build();

        RemovePeerGroupLedgerResponse response = blockingStub
                .withDeadlineAfter(350, TimeUnit.MILLISECONDS)
                .removePeerGroupLedger(request);

        return response.getResult();
    }

    public boolean removePeerGroupLedgerFuture(String peerId) throws InterruptedException {
        logger.debug("Contacting Super-Peer: removing peer, {}", peerId);
        if (!isActive) {
            logger.warn("client not active!");
            return false;
        }
        RemovePeerGroupLedgerRequest request = RemovePeerGroupLedgerRequest.newBuilder().setPeerId(peerId).build();

        RemovePeerGroupLedgerResponse response = blockingStub
                .withDeadlineAfter(350, TimeUnit.MILLISECONDS)
                .removePeerGroupLedger(request);

        ListenableFuture<RemovePeerGroupLedgerResponse> futureResponse = futureStub.removePeerGroupLedger(request);
        final CountDownLatch finishLatch = new CountDownLatch(1);

        Futures.addCallback(futureResponse, new FutureCallback<RemovePeerGroupLedgerResponse>() {
            @Override
            public void onSuccess(@Nullable RemovePeerGroupLedgerResponse result) {
                logger.debug("Successful request");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("AddObjectReference Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        RemovePeerGroupLedgerResponse removePeerGroupLedgerResponse;
        if (!futureResponse.isCancelled()) {
            try {
                removePeerGroupLedgerResponse = futureResponse.get();
                logger.info("Successful removePeerGroupLedger request");
                return removePeerGroupLedgerResponse.getResult();
            } catch (ExecutionException e) {
                // e.printStackTrace();
                logger.error("removePeerGroupLedger failed!");
                return false;
            }
        } else {
            logger.error("removePeerGroupLedger has been cancelled!");
            return false;
        }
    }

    public UpdatePeerPositionResponse updatePeerPosition(String peerId, VirtualPosition newPosition) {
        logger.debug("Contacting Super-Peer: updating peer {} position - {}", peerId, newPosition);
        if (!isActive) {
            logger.warn("client not active!");
            return UpdatePeerPositionResponse.builder().ack(false).newSuperPeer("").newGroup("").build();
        }
        PositionUpdateRequest request = PositionUpdateRequest.newBuilder().setPeerId(peerId).setPosition(newPosition).build();
        PositionUpdateResponse response = blockingStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS).updatePositionReference(request);

        return UpdatePeerPositionResponse.builder().ack(response.getAcknowledge()).newSuperPeer(response.getNewSuperPeer()).newGroup(response.getNewGroupName()).build();
    }

    public UpdatePeerPositionResponse updatePeerPositionFuture(String peerId, VirtualPosition newPosition) throws InterruptedException {
        UpdatePeerPositionResponse defaultResponse = UpdatePeerPositionResponse.builder().ack(false).newSuperPeer("").newGroup("").build();
        if (!isActive) {
            logger.warn("client not active!");
            return defaultResponse;
        }

        PositionUpdateRequest request = PositionUpdateRequest.newBuilder().setPeerId(peerId).setPosition(newPosition).build();
        logger.debug("Contacting Super-Peer: updating peer {} position - ({};{};{})", peerId, newPosition.getX(), newPosition.getY(), newPosition.getZ());
        ListenableFuture<PositionUpdateResponse> futureResponse = futureStub.updatePositionReference(request);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(futureResponse, new FutureCallback<PositionUpdateResponse>() {
            @Override
            public void onSuccess(@Nullable PositionUpdateResponse result) {
                logger.debug("Successful position update");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("Position update Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        PositionUpdateResponse positionUpdateResponse;

        if (!futureResponse.isCancelled()) {
            try {
                if (futureResponse.isDone()) {
                    positionUpdateResponse = futureResponse.get();
                } else {
                    positionUpdateResponse = futureResponse.get(500, TimeUnit.MILLISECONDS);
                }
                logger.debug("Update result: {} ; {} ; {}", positionUpdateResponse.getAcknowledge(),
                        positionUpdateResponse.getNewSuperPeer(), positionUpdateResponse.getNewGroupName());
            } catch (InterruptedException e) {
                logger.error("Position update Interrupted", e);
                Thread.currentThread().interrupt();
                return defaultResponse;
            } catch (TimeoutException e) {
                logger.error("Position update Timed out", e);
                // e.printStackTrace();
                return defaultResponse;
            } catch (ExecutionException e) {
                logger.error("Position update execution failed");
                // e.printStackTrace();
                return defaultResponse;
            }
        } else {
            logger.error("Position update has been cancelled!");
            return defaultResponse;
        }

        return UpdatePeerPositionResponse.builder().ack(positionUpdateResponse.getAcknowledge()).newSuperPeer(positionUpdateResponse.getNewSuperPeer()).newGroup(positionUpdateResponse.getNewGroupName()).build();
    }

    public int getSuperPeerReplicationFactor() {
        return superPeerReplicationFactor;
    }
}
