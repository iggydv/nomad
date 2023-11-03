package org.nomad.grpc.management.clients;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Empty;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.nomad.commons.NetworkUtility;
import org.nomad.grpc.groupstorage.AddToGroupLedgerRequest;
import org.nomad.grpc.groupstorage.AddToGroupLedgerResponse;
import org.nomad.grpc.groupstorage.DeleteObjectRequest;
import org.nomad.grpc.groupstorage.DeleteObjectResponse;
import org.nomad.grpc.groupstorage.GetObjectRequest;
import org.nomad.grpc.groupstorage.GetObjectResponse;
import org.nomad.grpc.groupstorage.GroupStorageServiceGrpc;
import org.nomad.grpc.groupstorage.HealthCheckResponse;
import org.nomad.grpc.groupstorage.PutObjectRequest;
import org.nomad.grpc.groupstorage.PutObjectResponse;
import org.nomad.grpc.groupstorage.RemovePeerGroupLedgerRequest;
import org.nomad.grpc.groupstorage.RemovePeerGroupLedgerResponse;
import org.nomad.grpc.groupstorage.UpdateObjectRequest;
import org.nomad.grpc.groupstorage.UpdateObjectResponse;
import org.nomad.pithos.mappers.CustomMappers;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.pithos.models.GameObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.nomad.grpc.groupstorage.HealthCheckResponse.ServingStatus.SERVING;

public class GroupStorageClient {
    private static final Logger logger = LoggerFactory.getLogger(GroupStorageClient.class);
    private static final RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
            .withDelay(Duration.ofMillis(20))
            .handle(Exception.class)
            .withMaxRetries(3);
    private final GroupStorageServiceGrpc.GroupStorageServiceBlockingStub blockingStub;
    private final GroupStorageServiceGrpc.GroupStorageServiceStub asyncStub;
    private final GroupStorageServiceGrpc.GroupStorageServiceFutureStub futureStub;
    private final ManagedChannel managedChannel;
    private final CustomMappers mapper = new CustomMappers();
    private final String server;
    private boolean isActive = false;
    private long rpcCount;

    /**
     * Construct client for accessing RouteGuide server using the existing channel.
     */
    public GroupStorageClient(Channel channel) {
        this.managedChannel = (ManagedChannel) channel;
        this.blockingStub = GroupStorageServiceGrpc.newBlockingStub(channel);
        this.asyncStub = GroupStorageServiceGrpc.newStub(channel);
        this.futureStub = GroupStorageServiceGrpc.newFutureStub(channel);
        this.server = channel.authority();
        this.isActive = true;
    }

    public long getRpcCount() {
        return rpcCount;
    }

    public void close() throws InterruptedException {
        this.isActive = false;
        managedChannel.shutdown().awaitTermination(60, TimeUnit.SECONDS);
    }

    public boolean isActive() {
        return isActive;
    }

    public String getClientHostname() {
        return server;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupStorageClient that = (GroupStorageClient) o;
        return blockingStub.equals(that.blockingStub) &&
                asyncStub.equals(that.asyncStub) &&
                managedChannel.equals(that.managedChannel) &&
                mapper.equals(that.mapper);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockingStub, asyncStub, managedChannel, mapper);
    }

    /*
     * ===================================================================
     * BLOCKING STUB
     * ===================================================================
     */

    /**
     * Blocking unary call. Calls putGameObject and prints the response.
     */
    public boolean putGameObject(GameObject gameObject) {
        logger.debug("Adding object: {}", gameObject.getId());

        PutObjectRequest request = PutObjectRequest.newBuilder()
                .setObject(GameObjectMapperImpl.INSTANCE.mapToGrpc(gameObject))
                .build();

        try {
            PutObjectResponse result = blockingStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS).put(request);
            return result.getResult();

        } catch (StatusRuntimeException e) {
            logger.error("RPC failed: {}", e.getStatus());
            return false;
        }
    }

    public boolean notifyObjectAdded(String objectId, String peerId, long TTL) {
        logger.debug("Adding object reference to ledger: {}", objectId);
        if (!isActive || managedChannel.isShutdown()) {
            logger.warn("The client wasn't active");
            return false;
        }

        AddToGroupLedgerRequest request = AddToGroupLedgerRequest.newBuilder()
                .setObjectId(objectId)
                .setPeerId(peerId)
                .setTtl(TTL)
                .build();

        AddToGroupLedgerResponse response = blockingStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS).addToGroupLedger(request);
        return response.getResult();
    }

    public boolean notifyRemovePeerFromGroupLedger(String peerId) {
        logger.debug("Sending request to remove peer from group-ledger: {}", peerId);
        if (!isActive || managedChannel.isShutdown()) {
            logger.warn("The client wasn't active");
            return false;
        }

        RemovePeerGroupLedgerRequest request = RemovePeerGroupLedgerRequest.newBuilder().setPeerId(peerId).build();

        RemovePeerGroupLedgerResponse response = blockingStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS).removePeerGroupLedger(request);
        return response.getResult();
    }

    /**
     * Blocking unary call. Calls getGameObject and prints the response.
     */
    public GameObject getGameObject(String id) throws NoSuchElementException {
        logger.debug("Getting object: {}", id);
        GetObjectRequest request = GetObjectRequest.newBuilder().setId(id).build();

        try {
            GetObjectResponse getObjectResponse = blockingStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS).get(request);
            if (getObjectResponse.getResult()) {
                return GameObjectMapperImpl.INSTANCE.mapToInternal(getObjectResponse.getObject());
            }
            throw new NoSuchElementException("Object with id: " + id + " not found");

        } catch (StatusRuntimeException e) {
            logger.error("RPC failed: {}", e.getStatus());
            throw new NoSuchElementException("Object with id: " + id + " not found");
        }
    }

    /**
     * Blocking unary call. Calls updateGameObject and prints the response.
     */
    public boolean updateGameObject(GameObject gameObject) {
        logger.debug("Updating object: {}", gameObject.getId());

        UpdateObjectRequest request = UpdateObjectRequest.newBuilder()
                .setObject(GameObjectMapperImpl.INSTANCE.mapToGrpc(gameObject))
                .build();

        try {
            UpdateObjectResponse result = blockingStub.withDeadlineAfter(500, TimeUnit.MILLISECONDS).update(request);
            return result.getResult();

        } catch (StatusRuntimeException e) {
            logger.warn("RPC failed: {}, {}", e.getStatus(), e.getMessage());
            return false;
        }
    }

    /**
     * Blocking unary call.  Calls putGameObject and prints the response.
     */
    public boolean deleteGameObject(String id) {
        logger.debug("Deleting object: {}", id);

        DeleteObjectRequest request = DeleteObjectRequest.newBuilder()
                .setId(id)
                .build();

        try {
            DeleteObjectResponse result = blockingStub.withDeadlineAfter(500, TimeUnit.MILLISECONDS).delete(request);
            return result.getResult();

        } catch (StatusRuntimeException e) {
            logger.warn("RPC failed: {}", e.getStatus());
            return false;
        }
    }

    public boolean healthCheck() {
        logger.debug("Checking server health");
        Empty request = Empty.newBuilder().build();
        try {
            HealthCheckResponse result = Failsafe.with(retryPolicy).onFailure((e) -> {
                logger.debug("Failed to check client health: {}", e);
            }).get(() -> blockingStub.healthCheck(request));

            logger.debug("The result status is: {}", result.getStatus());
            return result.getStatus().equals(SERVING);
        } catch (StatusRuntimeException e) {
            logger.warn("RPC to {} failed: {}", server, e.getStatus());
//            e.printStackTrace();
        }
        return false;
    }

    public boolean pingClient() {
        boolean result = NetworkUtility.pingClient(server);
        logger.debug("ping result: {}", result);
        return result;
    }

    /**
     * ===================================================================
     * FUTURE STUB
     * ===================================================================
     */
    public boolean putGameObjectFuture(GameObject gameObject) throws InterruptedException {
        logger.debug("Adding object: {}", gameObject.getId());

        PutObjectRequest request = PutObjectRequest.newBuilder()
                .setObject(GameObjectMapperImpl.INSTANCE.mapToGrpc(gameObject))
                .build();

        ListenableFuture<PutObjectResponse> futureResponse = futureStub.put(request);
        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(futureResponse, new FutureCallback<PutObjectResponse>() {
            @Override
            public void onSuccess(@Nullable PutObjectResponse result) {
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        PutObjectResponse putObjectResponse;

        if (!futureResponse.isCancelled()) {
            try {
                if (futureResponse.isDone()) {
                    putObjectResponse = futureResponse.get();
                } else {
                    putObjectResponse = futureResponse.get(50, TimeUnit.MILLISECONDS);
                }
                logger.debug("Put result: {}", putObjectResponse.getResult());

            } catch (InterruptedException e) {
                logger.error("Put result Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (TimeoutException e) {
                logger.error("Put result Timed out", e);
                // e.printStackTrace();
                return false;
            } catch (ExecutionException e) {
                logger.error("Put result execution failed");
                // e.printStackTrace();
                return false;
            }
        } else {
            logger.error("Put has been cancelled!");
            return false;
        }
        logger.debug("Successful Put request");
        return putObjectResponse.getResult();
    }

    public GameObject getGameObjectFuture(String id) throws NoSuchElementException, InterruptedException {
        logger.debug("Getting object: {}", id);

        if (!isActive || managedChannel.isShutdown()) {
            logger.warn("The client wasn't active");
            throw new NoSuchElementException();
        }

        GetObjectRequest request = GetObjectRequest.newBuilder().setId(id).build();
        ListenableFuture<GetObjectResponse> future = futureStub.get(request);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(future, new FutureCallback<GetObjectResponse>() {
            @Override
            public void onSuccess(@Nullable GetObjectResponse result) {
                logger.debug("Successful request");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("Get Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        GetObjectResponse getObjectResponse;

        if (!future.isCancelled()) {
            try {
                if (future.isDone()) {
                    getObjectResponse = future.get();
                } else {
                    getObjectResponse = future.get(50, TimeUnit.MILLISECONDS);
                }
                logger.debug("Get result: {}", getObjectResponse.getResult());

            } catch (InterruptedException e) {
                logger.debug("Get Interrupted", e);
                Thread.currentThread().interrupt();
                throw new NoSuchElementException();
            } catch (TimeoutException e) {
                logger.error("Get Timed out", e);
                // e.printStackTrace();
                throw new NoSuchElementException();
            } catch (ExecutionException e) {
                logger.error("Get execution failed");
                // e.printStackTrace();
                throw new NoSuchElementException();
            }
        } else {
            logger.error("Get has been cancelled!");
            throw new NoSuchElementException();
        }

        if (getObjectResponse.getResult()) {
            logger.debug("successful Get request!");
            return GameObjectMapperImpl.INSTANCE.mapToInternal(getObjectResponse.getObject());
        }
        throw new NoSuchElementException("Object with id: " + id + " not found");
    }

    /**
     * Blocking unary call. Calls updateGameObject and prints the response.
     */
    public boolean updateGameObjectFuture(GameObject gameObject) throws InterruptedException {
        logger.debug("Updating object: {}", gameObject.getId());

        UpdateObjectRequest request = UpdateObjectRequest.newBuilder()
                .setObject(GameObjectMapperImpl.INSTANCE.mapToGrpc(gameObject))
                .build();


        ListenableFuture<UpdateObjectResponse> futureResponse = futureStub.update(request);
        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(futureResponse, new FutureCallback<UpdateObjectResponse>() {
            @Override
            public void onSuccess(@Nullable UpdateObjectResponse result) {
                logger.debug("Successful Update");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("Update Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        UpdateObjectResponse updateObjectResponse;

        if (!futureResponse.isCancelled()) {
            try {
                if (futureResponse.isDone()) {
                    updateObjectResponse = futureResponse.get();
                } else {
                    updateObjectResponse = futureResponse.get(50, TimeUnit.MILLISECONDS);
                }
                logger.debug("Update result: {}", updateObjectResponse.getResult());

            } catch (InterruptedException e) {
                logger.error("Update result Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (TimeoutException e) {
                logger.error("Put result Timed out", e);
                // e.printStackTrace();
                return false;
            } catch (ExecutionException e) {
                logger.error("Update result execution failed");
                // e.printStackTrace();
                return false;
            }
        } else {
            logger.error("Update has been cancelled!");
            return false;
        }
        return updateObjectResponse.getResult();
    }

    public boolean notifyObjectAddedFuture(String objectId, String peerId, long TTL) throws InterruptedException {
        AddToGroupLedgerRequest request = AddToGroupLedgerRequest.newBuilder()
                .setObjectId(objectId)
                .setPeerId(peerId)
                .setTtl(TTL)
                .build();

        ListenableFuture<AddToGroupLedgerResponse> futureResponse = futureStub
//                .withDeadlineAfter(100, TimeUnit.MILLISECONDS)
                .addToGroupLedger(request);

        final CountDownLatch finishLatch = new CountDownLatch(1);
        Futures.addCallback(futureResponse, new FutureCallback<AddToGroupLedgerResponse>() {
            @Override
            public void onSuccess(@Nullable AddToGroupLedgerResponse result) {
                logger.debug("Successful Add to ledger");
                finishLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                logger.error("Add to ledger Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        AddToGroupLedgerResponse addToGroupLedgerResponse;

        if (!futureResponse.isCancelled()) {
            try {
                if (futureResponse.isDone()) {
                    addToGroupLedgerResponse = futureResponse.get();
                } else {
                    addToGroupLedgerResponse = futureResponse.get(50, TimeUnit.MILLISECONDS);
                }
                logger.debug("Add to ledger result: {}", addToGroupLedgerResponse.getResult());

            } catch (InterruptedException e) {
                logger.error("Add to ledger Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (TimeoutException e) {
                logger.error("Add to ledger Timed out", e);
                // e.printStackTrace();
                return false;
            } catch (ExecutionException e) {
                logger.error("Add to ledger execution failed");
//                e.printStackTrace();
                return false;
            }
        } else {
            logger.error("Add to ledger has been cancelled!");
            return false;
        }
        return addToGroupLedgerResponse.getResult();
    }

    public boolean notifyRemovePeerFromGroupLedgerFuture(String peerId) throws InterruptedException {
        logger.debug("Sending request to remove peer from group-ledger: {}", peerId);
        if (!isActive || managedChannel.isShutdown()) {
            logger.warn("The client wasn't active");
            return false;
        }

        RemovePeerGroupLedgerRequest request = RemovePeerGroupLedgerRequest.newBuilder().setPeerId(peerId).build();

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
                logger.error("Remove from group ledger Failed");
                finishLatch.countDown();
            }
        }, MoreExecutors.directExecutor());

        finishLatch.await();
        RemovePeerGroupLedgerResponse removePeerGroupLedgerResponse;

        if (!futureResponse.isCancelled()) {
            try {
                if (futureResponse.isDone()) {
                    removePeerGroupLedgerResponse = futureResponse.get();
                } else {
                    removePeerGroupLedgerResponse = futureResponse.get(50, TimeUnit.MILLISECONDS);
                }
                logger.debug("Remove from group ledger result: {}", removePeerGroupLedgerResponse.getResult());

            } catch (InterruptedException e) {
                logger.error("Remove from group ledger Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (TimeoutException e) {
                logger.error("Remove from group ledger Timed out", e);
                // e.printStackTrace();
                return false;
            } catch (ExecutionException e) {
                logger.error("Remove from group ledger execution failed");
                // e.printStackTrace();
                return false;
            }
        } else {
            logger.error("Add to ledger has been cancelled!");
            return false;
        }
        return removePeerGroupLedgerResponse.getResult();
    }

    /**
     * ===================================================================
     * ASYNC STUB
     * ===================================================================
     */
    public void asyncNotifyObjectAdded(String objectId, String peerId, long TTL) throws InterruptedException {
        logger.debug("Adding object reference to ledger: {}", objectId);

        if (!isActive) {
            logger.warn("The client wasn't active");
            return;
        }

        AddToGroupLedgerRequest request = AddToGroupLedgerRequest.newBuilder()
                .setObjectId(objectId)
                .setPeerId(peerId)
                .setTtl(TTL)
                .build();

        GroupStorageServiceGrpc.GroupStorageServiceStub stub = asyncStub.withDeadlineAfter(350, TimeUnit.MILLISECONDS);

        final AddToGroupLedgerResponse[] addToGroupLedgerResponses = {null};
        CountDownLatch finishLatch = new CountDownLatch(1);

        stub.addToGroupLedger(request, new StreamObserver<AddToGroupLedgerResponse>() {
            @Override
            public void onNext(AddToGroupLedgerResponse response) {
                addToGroupLedgerResponses[0] = response;
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Failed");
                isActive = false;
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.debug("completed");
                finishLatch.countDown();
            }
        });

        finishLatch.await();

        try {
            if (!addToGroupLedgerResponses[0].getResult()) {
                logger.error("Peer {} could not add objectID {} to ledger!", peerId, objectId);
            }
        } catch (Exception e) {
            logger.error("ERROR!");
            // e.printStackTrace();
        }
    }

    public void asyncNotifyRemovePeerFromGroupLedger(String peerId) throws InterruptedException {
        logger.debug("Sending request to remove peer from group-ledger: {}", peerId);
        if (!isActive || managedChannel.isShutdown()) {
            logger.warn("The client wasn't active");
            return;
        }

        RemovePeerGroupLedgerRequest request = RemovePeerGroupLedgerRequest.newBuilder()
                .setPeerId(peerId).build();

        GroupStorageServiceGrpc.GroupStorageServiceStub stub = asyncStub.withDeadlineAfter(500, TimeUnit.MILLISECONDS);

        final RemovePeerGroupLedgerResponse[] removePeerGroupLedgerResponses = {null};
        CountDownLatch finishLatch = new CountDownLatch(1);

        stub.removePeerGroupLedger(request, new StreamObserver<RemovePeerGroupLedgerResponse>() {
            @Override
            public void onNext(RemovePeerGroupLedgerResponse response) {
                removePeerGroupLedgerResponses[0] = response;
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Failed");
                isActive = false;
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.debug("completed");
                finishLatch.countDown();
            }
        });

        finishLatch.await();

        if (!removePeerGroupLedgerResponses[0].getResult()) {
            logger.error("Peer could not remove peer {} from ledger!", peerId);
        }
    }

    /**
     * ===================================================================
     * TEST FUNCTIONS
     * ===================================================================
     */
    public void doClientWork(AtomicBoolean done, GameObject gameObject) throws InterruptedException {
        Random random = new Random();

        long putCount = 0;
        long getCount = 0;
        long updateCount = 0;
        long notifyObjectAddedCount = 0;
        long notifyRemovePeerCount = 0;

        while (!done.get()) {
            // Pick a random CRUD action to take.
            gameObject.setId("" + random.nextInt(500));
            int command = random.nextInt(5);
            if (command == 0) {
                putGameObjectFuture(gameObject);
                putCount++;
                continue;
            }
            if (command == 1) {
                notifyObjectAddedCount++;
                notifyObjectAddedFuture(gameObject.getId(), NetworkUtility.getID(), gameObject.getTtl());
            } else if (command == 2) {
                notifyRemovePeerCount++;
                notifyRemovePeerFromGroupLedgerFuture(NetworkUtility.getID());
            } else if (command == 3) {
                try {
                    getCount++;
                    getGameObjectFuture("" + random.nextInt(500));
                    continue;
                } catch (NoSuchElementException e) {
                    logger.debug("not found");
                }
            } else if (command == 4) {
                updateCount++;
                updateGameObjectFuture(gameObject);
            } else {
                throw new AssertionError();
            }
            rpcCount++;
        }
        printResults(putCount, getCount, updateCount, notifyObjectAddedCount, notifyRemovePeerCount);
    }

    public void doClientWorkBlocking(AtomicBoolean done, GameObject gameObject) {
        Random random = new Random();

        long putCount = 0;
        long getCount = 0;
        long updateCount = 0;
        long notifyObjectAddedCount = 0;
        long notifyRemovePeerCount = 0;

        while (!done.get()) {
            // Pick a random CRUD action to take.
            gameObject.setId("" + random.nextInt(500));
            int command = random.nextInt(5);
            if (command == 0) {
                putGameObject(gameObject);
                putCount++;
                continue;
            }
            if (command == 1) {
                notifyObjectAddedCount++;
                notifyObjectAdded(gameObject.getId(), NetworkUtility.getID(), gameObject.getTtl());
            } else if (command == 2) {
                notifyRemovePeerCount++;
                notifyRemovePeerFromGroupLedger(NetworkUtility.getID());
            } else if (command == 3) {
                try {
                    getCount++;
                    getGameObject("" + random.nextInt(500));
                    continue;
                } catch (NoSuchElementException e) {
                    logger.debug("not found");
                }
            } else if (command == 4) {
                updateCount++;
                updateGameObject(gameObject);
            } else {
                throw new AssertionError();
            }
            rpcCount++;
        }
        printResults(putCount, getCount, updateCount, notifyObjectAddedCount, notifyRemovePeerCount);
    }

    private void printResults(long putCount, long getCount, long updateCount, long notifyObjectAddedCount, long notifyRemovePeerCount) {
        logger.error("total RPC count: {}\n", rpcCount);
        logger.error("\ngets: {}\n" +
                "puts: {}\n" +
                "updates: {}\n" +
                "notifyObject: {}\n" +
                "notifyRemovePeer: {}", getCount, putCount, updateCount, notifyObjectAddedCount, notifyRemovePeerCount);
    }
}
