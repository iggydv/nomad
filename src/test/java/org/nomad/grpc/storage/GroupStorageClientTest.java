package org.nomad.grpc.storage;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.grpc.management.clients.GroupStorageClient;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.services.GroupStorageService;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.local.LocalStorage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class GroupStorageClientTest {

    private static Server server;
    private static ManagedChannel channel;
    long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject = GameObject.builder()
            .id("1")
            .value("hello".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(600)
            .build();
    @Mock
    LocalStorage storage;
    @Mock
    GroupLedger groupLedger;
    @Mock
    SuperPeerClient superPeerClient;
    private GroupStorageClient testClient;

    @BeforeEach
    public void setup() throws Exception {
        GroupStorageService myService = new GroupStorageService(storage);
        myService.updateHostname("localhost:8099");
        myService.updateSuperPeerClient(superPeerClient);
        server = ServerBuilder.forPort(8099).addService(myService).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
        testClient = new GroupStorageClient(channel);
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        server.shutdown();
        server.awaitTermination();
        channel.shutdown();
        groupLedger.clearAll();
    }

    @Test
    void testPutGameObject() {
        Mockito.when(storage.put(Mockito.any())).thenReturn(true);
        Assertions.assertTrue(testClient.putGameObject(testObject));
    }

    @Test
    void testGetGameObject() {
        Mockito.when(storage.get(Mockito.anyString())).thenReturn(testObject);
        GameObject retrievedObject = testClient.getGameObject("1");

        Assertions.assertNotNull(retrievedObject);
        Assertions.assertEquals(testObject, retrievedObject);
    }

    @Test
    void testUpdateGameObject() {
        Mockito.when(storage.update(Mockito.any())).thenReturn(true);
        Assertions.assertTrue(testClient.updateGameObject(testObject));
    }

    @Test
    void testDeleteGameObject() {
        Mockito.when(storage.delete(Mockito.anyString())).thenReturn(true);
        Assertions.assertTrue(testClient.deleteGameObject("1"));
    }
}