package org.nomad.grpc.storage;

import io.grpc.internal.testing.StreamRecorder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.grpc.groupstorage.AddToGroupLedgerRequest;
import org.nomad.grpc.groupstorage.AddToGroupLedgerResponse;
import org.nomad.grpc.groupstorage.GetObjectRequest;
import org.nomad.grpc.groupstorage.GetObjectResponse;
import org.nomad.grpc.groupstorage.HealthCheckResponse;
import org.nomad.grpc.groupstorage.PutObjectRequest;
import org.nomad.grpc.groupstorage.PutObjectResponse;
import org.nomad.grpc.groupstorage.UpdateObjectRequest;
import org.nomad.grpc.groupstorage.UpdateObjectResponse;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.services.GroupStorageService;
import org.nomad.grpc.models.GameObjectGrpc;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.mappers.GameObjectMapperImpl;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.local.LocalStorage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
public class GroupStorageServerTest {
    @Mock
    LocalStorage storage;
    @Mock
    SuperPeerClient superPeerClient;
    long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject = GameObject.builder()
            .id("0")
            .value("0".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(unixTime + 600L)
            .build();

    private final GameObjectGrpc grpcObject = GameObjectMapperImpl.INSTANCE.mapToGrpc(testObject);

    @InjectMocks
    private GroupStorageService myService;

    @BeforeEach
    public void setup() {
        myService.setStatus(HealthCheckResponse.ServingStatus.SERVING);
        myService.updateSuperPeerClient(superPeerClient);
    }

    @Test
    void testGet() throws Exception {
        Mockito.when(storage.get(Mockito.anyString())).thenReturn(testObject);
        GetObjectRequest request = GetObjectRequest.newBuilder().setId("1").build();
        StreamRecorder<GetObjectResponse> responseObserver = StreamRecorder.create();
        myService.get(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<GetObjectResponse> result = responseObserver.getValues();
        GetObjectResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, GetObjectResponse.newBuilder().setObject(grpcObject).setResult(true).build());
    }

    @Test
    void testPut() throws Exception {
        Mockito.when(storage.put(Mockito.any())).thenReturn(true);
        PutObjectRequest request = PutObjectRequest.newBuilder().setObject(grpcObject).build();
        StreamRecorder<PutObjectResponse> responseObserver = StreamRecorder.create();
        myService.put(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<PutObjectResponse> result = responseObserver.getValues();
        PutObjectResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, PutObjectResponse.newBuilder().setResult(true).build());
    }

    @Test
    void testUpdate() throws Exception {
        Mockito.when(storage.update(Mockito.any())).thenReturn(true);
        UpdateObjectRequest request = UpdateObjectRequest.newBuilder().setObject(grpcObject).build();
        StreamRecorder<UpdateObjectResponse> responseObserver = StreamRecorder.create();
        myService.update(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<UpdateObjectResponse> result = responseObserver.getValues();
        UpdateObjectResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, UpdateObjectResponse.newBuilder().setResult(true).build());
    }

    @Test
    void testAddToGroupLedger() throws Exception {
        AddToGroupLedgerRequest request = AddToGroupLedgerRequest.newBuilder().setPeerId("1").setObjectId("1").build();
        StreamRecorder<AddToGroupLedgerResponse> responseObserver = StreamRecorder.create();
        myService.addToGroupLedger(request, responseObserver);
        myService.addToGroupLedger(request, responseObserver);
        myService.addToGroupLedger(request, responseObserver);
        myService.addToGroupLedger(request, responseObserver);
        myService.addToGroupLedger(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<AddToGroupLedgerResponse> result = responseObserver.getValues();
        AddToGroupLedgerResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(5, result.size());
        assertEquals(response, AddToGroupLedgerResponse.newBuilder().setResult(true).build());
        assertTrue(GroupLedger.getInstance().getObjectLedger().containsKey("1"));
    }
}