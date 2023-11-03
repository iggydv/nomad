package org.nomad.grpc.api.service;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import org.nomad.grpc.GRPCUtils;
import org.nomad.grpc.models.GameObjectGrpc;
import org.nomad.grpc.peerstorageservice.DeleteObjectRequest;
import org.nomad.grpc.peerstorageservice.DeleteObjectResponse;
import org.nomad.grpc.peerstorageservice.GetObjectRequest;
import org.nomad.grpc.peerstorageservice.GetObjectResponse;
import org.nomad.grpc.peerstorageservice.PeerStorageServiceGrpc;
import org.nomad.grpc.peerstorageservice.PutObjectRequest;
import org.nomad.grpc.peerstorageservice.PutObjectResponse;
import org.nomad.grpc.peerstorageservice.UpdateObjectRequest;
import org.nomad.grpc.peerstorageservice.UpdateObjectResponse;
import org.nomad.pithos.components.GenericGroupLedger;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.PeerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * Used for testing rest vs gRPC performance
 */
@Component
public class PeerStorageService extends PeerStorageServiceGrpc.PeerStorageServiceImplBase {
    private final Logger logger = LoggerFactory.getLogger(PeerStorageService.class);
    private final PeerStorage storage;
    private final GenericGroupLedger groupLedger;

    @Autowired
    public PeerStorageService(PeerStorage storage) {
        // Assume the storage unit has been initialized already
        this.storage = storage;
        this.groupLedger = GroupLedger.getInstance();
    }

    @Override
    public void get(GetObjectRequest getObjectRequest, StreamObserver<GetObjectResponse> responseObserver) {
        logger.info("gRPC 'get' received");

        String id = getObjectRequest.getId();
        GameObject gameObject;
        GameObjectGrpc gameObjectGrpc = GameObjectGrpc.getDefaultInstance();
        boolean result = false;
        StatusException status = Status.INTERNAL.asException();
        try {
            gameObject = storage.get(id, true, true);
            gameObjectGrpc = GameObjectMapperImpl.INSTANCE.mapToGrpc(gameObject);
            result = true;
        } catch (NoSuchElementException e) {
            logger.warn("Not found: {}", id);
            status = Status.NOT_FOUND.asException();
        } catch (InterruptedException exception) {
            logger.error("Interrupted!");
            Thread.currentThread().interrupt();
        }

        GetObjectResponse response = GetObjectResponse.newBuilder()
                .setObject(gameObjectGrpc)
                .setResult(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onError(status);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void put(PutObjectRequest putObjectRequest, StreamObserver<PutObjectResponse> responseObserver) {
        logger.info("gRPC 'put' received");

        GameObjectGrpc gameObjectGrpc = putObjectRequest.getObject();
        GameObject gameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObjectGrpc);
        long timestamp = gameObject.getCreationTime();
        long TTL = timestamp + gameObject.getTtl();
        gameObject.setTtl(TTL);

        boolean result = false;
        StatusException status = Status.INTERNAL.asException();

        try {
            result = storage.put(gameObject, true, true);
        } catch (InterruptedException | IOException | DuplicateKeyException e) {
            logger.error("gRPC 'put' failed");
            status = Status.ALREADY_EXISTS.asException();
        }

        PutObjectResponse response = PutObjectResponse.newBuilder()
                .setResult(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onError(status);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void update(UpdateObjectRequest updateObjectRequest, StreamObserver<UpdateObjectResponse> responseObserver) {
        logger.info("gRPC 'update' received");

        GameObjectGrpc gameObjectGrpc = updateObjectRequest.getObject();
        GameObject gameObject = GameObjectMapperImpl.INSTANCE.mapToInternal(gameObjectGrpc);
        long timestamp = gameObject.getCreationTime();
        long TTL = timestamp + gameObject.getTtl();
        // Set the adjusted TTL
        gameObject.setTtl(TTL);

        boolean result = false;

        String id = gameObject.getId();

        try {
            result = storage.update(gameObject);
        } catch (NoSuchElementException | InterruptedException e) {
            logger.error("Not found: {}", id);
        }

        UpdateObjectResponse response = UpdateObjectResponse.newBuilder()
                .setResult(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void delete(DeleteObjectRequest deleteObjectRequest, StreamObserver<DeleteObjectResponse> responseObserver) {
        logger.info("gRPC 'delete' received");

        boolean result = false;
        String id = deleteObjectRequest.getId();
        try {
            result = storage.delete(id);
        } catch (NoSuchElementException | InterruptedException e) {
            logger.warn("Not Found: {}", id);
        }

        DeleteObjectResponse response = DeleteObjectResponse.newBuilder()
                .setResult(result)
                .build();

        GRPCUtils.checkGrpcCallStatus(responseObserver);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

}
