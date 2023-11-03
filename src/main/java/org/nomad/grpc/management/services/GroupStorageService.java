package org.nomad.grpc.management.services;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.nomad.commons.Base64Utility;
import org.nomad.config.Config;
import org.nomad.grpc.GRPCUtils;
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
import org.nomad.grpc.management.clients.GroupStorageClient;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.models.GameObjectGrpc;
import org.nomad.pithos.components.GenericGroupLedger;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.pithos.models.GameObject;
import org.nomad.pithos.models.MetaData;
import org.nomad.storage.local.LocalStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Component
public class GroupStorageService extends GroupStorageServiceGrpc.GroupStorageServiceImplBase {
    private final Logger logger = LoggerFactory.getLogger(GroupStorageService.class);
    private final LocalStorage storage;
    private final GenericGroupLedger groupLedger;
    private List<GroupStorageClient> clients = new ArrayList<>();
    private SuperPeerClient superPeerClient;
    private String hostname;
    private HealthCheckResponse.ServingStatus status;

    @Autowired
    public GroupStorageService(LocalStorage storage) {
        // Assume the storage unit has been initialized already
        this.storage = storage;
        this.groupLedger = GroupLedger.getInstance();
    }

    public void updateGroupStorageClients(List<GroupStorageClient> clients) {
        this.clients = clients;
    }

    public void updateSuperPeerClient(SuperPeerClient superPeerClient) {
        this.superPeerClient = superPeerClient;
        if (this.superPeerClient.isActive()) {
            logger.info("Super-client is active!");
        }
    }

    public void updateHostname(String hostname) {
        this.hostname = hostname;
    }

    public void setStatus(HealthCheckResponse.ServingStatus status) {
        this.status = status;
    }

    @Override
    public void get(GetObjectRequest getObjectRequest, StreamObserver<GetObjectResponse> responseObserver) {
        logger.debug("gRPC 'get' received");

        String id = getObjectRequest.getId();
        GameObject gameObject;
        GameObjectGrpc gameObjectGrpc = GameObjectGrpc.getDefaultInstance();
        boolean result = false;

        try {
            gameObject = storage.get(id);
            gameObjectGrpc = GameObjectMapperImpl.INSTANCE.mapToGrpc(gameObject);
            result = true;
        } catch (NoSuchElementException e) {
            logger.warn("Not found: {}", id);
        }

        GetObjectResponse response = GetObjectResponse.newBuilder()
                .setObject(gameObjectGrpc)
                .setResult(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void put(PutObjectRequest putObjectRequest, StreamObserver<PutObjectResponse> responseObserver) {
        logger.debug("gRPC 'put' received");

        GameObjectGrpc gameObjectGrpc = putObjectRequest.getObject();
        GameObject gameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObjectGrpc);
        String id = gameObject.getId();
        long ttl = gameObject.getTtl();
        // defaults to true - we don't want to fail if the object exists in group storage.
        boolean result = true;

        if (!groupLedger.thisPeerContainsObject(hostname, id)) {
            result = storage.put(gameObject);
            if (result) {
                notifyAllClientsObjectAdded(id, hostname, ttl);
            }
        }

        PutObjectResponse response = PutObjectResponse.newBuilder()
                .setResult(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void update(UpdateObjectRequest updateObjectRequest, StreamObserver<UpdateObjectResponse> responseObserver) {
        logger.debug("gRPC 'update' received");

        GameObjectGrpc gameObjectGrpc = updateObjectRequest.getObject();
        GameObject gameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObjectGrpc);
        boolean result = false;

        String id = gameObject.getId();
        long ttl = gameObject.getTtl();

        boolean isPut = !groupLedger.thisPeerContainsObject(hostname, id);

        try {
            result = storage.update(gameObject);
        } catch (NoSuchElementException e) {
            logger.warn("Not found: {}", id);
        }
        UpdateObjectResponse response = UpdateObjectResponse.newBuilder()
                .setResult(result)
                .build();

        if (isPut && result) {
            notifyAllClientsObjectAdded(id, hostname, ttl);
        }

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    protected void notifyAllClientsObjectAdded(String id, String hostname, long ttl) {
        try {
            logger.debug("notifying super-peer to add object reference - {}:{}", id, hostname);
            superPeerClient.addObjectReferenceFuture(id, hostname, ttl);
            if (!clients.isEmpty()) {
                for (GroupStorageClient client : clients) {
                    if (client.isActive()) {
                        logger.debug("notifying client: {} to add object reference - {}:{}", client.getClientHostname(), id, hostname);
                        client.notifyObjectAddedFuture(id, hostname, ttl);
                    } else {
                        logger.warn("Not contacting inactive client {}", client.getClientHostname());
                    }
                }
            }
        } catch (InterruptedException e) {
            logger.error("Error occurred while notifying super peer that an object was added");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void delete(DeleteObjectRequest deleteObjectRequest, StreamObserver<DeleteObjectResponse> responseObserver) {
        logger.debug("gRPC 'delete' received");

        boolean result = false;
        String id = deleteObjectRequest.getId();
        try {
            result = storage.delete(id);
        } catch (NoSuchElementException e) {
            logger.warn("Not Found: {}", id);
        }
        DeleteObjectResponse response = DeleteObjectResponse.newBuilder()
                .setResult(result)
                .build();
        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addToGroupLedger(AddToGroupLedgerRequest addToGroupLedgerRequest, StreamObserver<AddToGroupLedgerResponse> responseObserver) {
        logger.debug("gRPC 'addToGroupLedger' received");

        boolean result;
        String objectId = addToGroupLedgerRequest.getObjectId();
        String peerId = addToGroupLedgerRequest.getPeerId();
        long ttl = addToGroupLedgerRequest.getTtl();

        MetaData.MetaDataBuilder metaDataBuilder = MetaData.builder().ttl(ttl);

        boolean objectLedgerResult = groupLedger.addToObjectLedger(objectId, metaDataBuilder.id(peerId).build());
        boolean peerLedgerResult = groupLedger.addToPeerLedger(peerId, metaDataBuilder.id(objectId).build());

        result = objectLedgerResult && peerLedgerResult;

        AddToGroupLedgerResponse response = AddToGroupLedgerResponse.newBuilder().setResult(result).build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void removePeerGroupLedger(RemovePeerGroupLedgerRequest addToGroupLedgerRequest, StreamObserver<RemovePeerGroupLedgerResponse> responseObserver) {
        logger.debug("gRPC 'removePeerGroupLedger' received");

        String peerId = addToGroupLedgerRequest.getPeerId();

        logger.debug("Removing peer from Group Ledger - {}", peerId);
        groupLedger.removePeerFromGroupLedger(peerId);
        groupLedger.printObjectLedger();
        groupLedger.printPeerLedger();

        RemovePeerGroupLedgerResponse response = RemovePeerGroupLedgerResponse.newBuilder().setResult(true).build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void healthCheck(Empty request, StreamObserver<HealthCheckResponse> responseObserver) {
        logger.debug("gRPC 'checkHealth' received");

        HealthCheckResponse response = HealthCheckResponse.newBuilder()
                .setStatus(status)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void healthCheckWatch(Empty request, StreamObserver<HealthCheckResponse> responseObserver) {
        logger.debug("gRPC 'checkHealthWatch' received");

        HealthCheckResponse response = HealthCheckResponse.newBuilder()
                .setStatus(status)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
