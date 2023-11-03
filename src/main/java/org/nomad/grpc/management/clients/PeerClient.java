package org.nomad.grpc.management.clients;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Empty;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.nomad.grpc.peerservice.AddPeerRequest;
import org.nomad.grpc.peerservice.AddPeerResponse;
import org.nomad.grpc.peerservice.PeerServiceGrpc;
import org.nomad.grpc.peerservice.RemovePeerRequest;
import org.nomad.grpc.peerservice.RemovePeerResponse;
import org.nomad.grpc.peerservice.RepairObjectRequest;
import org.nomad.grpc.peerservice.RepairObjectResponse;
import org.nomad.grpc.peerservice.SuperPeerLeaveResponse;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class PeerClient {
    private static final Logger logger = LoggerFactory.getLogger(PeerClient.class);

    private final PeerServiceGrpc.PeerServiceBlockingStub blockingStub;
    private final PeerServiceGrpc.PeerServiceStub asyncStub;
    private final PeerServiceGrpc.PeerServiceFutureStub futureStub;
    private final ManagedChannel managedChannel;

    private VirtualPosition veLocation;

    public PeerClient(Channel channel) {
        this.managedChannel = (ManagedChannel) channel;
        this.blockingStub = PeerServiceGrpc.newBlockingStub(channel);
        this.asyncStub = PeerServiceGrpc.newStub(channel);
        this.futureStub = PeerServiceGrpc.newFutureStub(channel);
    }

    public void close() throws InterruptedException {
        managedChannel.shutdown().awaitTermination(500, TimeUnit.MILLISECONDS);
    }

    public String getClientIp() {
        return managedChannel.authority();
    }

    public boolean repairObjects(ObjectList<String> objectsToRepair) {
        RepairObjectRequest request = RepairObjectRequest.newBuilder().addAllObjectIds(objectsToRepair).build();
        logger.info("Sending 'repairObjects' request to {}", managedChannel.authority());
        RepairObjectResponse response = blockingStub.withDeadlineAfter(60, TimeUnit.SECONDS).repairObjects(request);
        return response.getResponse();
    }

    public boolean repairObjectsFuture(Collection<String> objectsToRepair) throws InterruptedException {
        RepairObjectRequest request = RepairObjectRequest.newBuilder().addAllObjectIds(objectsToRepair).build();
        logger.info("Sending 'repairObjects' request to {}", managedChannel.authority());
        ListenableFuture<RepairObjectResponse> futureResponse = futureStub.repairObjects(request);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(futureResponse, new FutureCallback<RepairObjectResponse>() {
            @Override
            public void onSuccess(@Nullable RepairObjectResponse result) {
                logger.info("Successful repair");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("Repair Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        RepairObjectResponse repairObjectResponse;

        if (!futureResponse.isCancelled()) {
            try {
                repairObjectResponse = futureResponse.get();
                logger.debug("Repair result: {}", repairObjectResponse.getResponse());
            } catch (InterruptedException e) {
                logger.error("Repair Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                logger.error("Repair execution failed");
                // e.printStackTrace();
                return false;
            }
        } else {
            logger.error("Repair has been cancelled!");
            return false;
        }

        return repairObjectResponse.getResponse();
    }

    public boolean removePeer(String host) {
        RemovePeerRequest request = RemovePeerRequest.newBuilder().setHost(host).build();
        logger.info("Sending 'removePeer' request to for groupStorage hostname {}", host);
        RemovePeerResponse response = blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS).removePeer(request);
        return response.getResponse();
    }

    public boolean addGroupStoragePeer(String host) {
        AddPeerRequest request = AddPeerRequest.newBuilder().setHost(host).build();
        logger.info("Sending 'addPeer' request to {}", getClientIp());
        AddPeerResponse response = blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS).addPeer(request);
        return response.getResponse();
    }

    public boolean handleSuperPeerLeave() {
        com.google.protobuf.Empty request = Empty.newBuilder().build();
        logger.info("Sending 'handleSuperPeerLeave' request to {}", getClientIp());
        SuperPeerLeaveResponse response = blockingStub.handleSuperPeerLeave(request);
        return response.getResponse();
    }

    public boolean handleSuperPeerLeaveFuture() throws InterruptedException {
        com.google.protobuf.Empty request = Empty.newBuilder().build();
        logger.info("Sending 'handleSuperPeerLeave' request to {}", getClientIp());
        ListenableFuture<SuperPeerLeaveResponse> futureResponse = futureStub.handleSuperPeerLeave(request);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(futureResponse, new FutureCallback<SuperPeerLeaveResponse>() {
            @Override
            public void onSuccess(@Nullable SuperPeerLeaveResponse result) {
                logger.info("Successful Super Peer leave");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("Super Peer leave Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        SuperPeerLeaveResponse superPeerLeaveResponse;

        if (!futureResponse.isCancelled()) {
            try {
                superPeerLeaveResponse = futureResponse.get();
                logger.debug("Super Peer leave result: {}", superPeerLeaveResponse.getResponse());
            } catch (InterruptedException e) {
                logger.error("Super Peer leave Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                logger.error("Super Peer leave execution failed");
                // e.printStackTrace();
                return false;
            }
        } else {
            logger.error("Super Peer leave has been cancelled!");
            return false;
        }

        return superPeerLeaveResponse.getResponse();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerClient that = (PeerClient) o;
        return blockingStub.equals(that.blockingStub) && asyncStub.equals(that.asyncStub) && managedChannel.equals(that.managedChannel) && veLocation.equals(that.veLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockingStub, asyncStub, managedChannel, veLocation);
    }
}
