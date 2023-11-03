package org.nomad.grpc.management;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.grpc.management.clients.PeerClient;
import org.nomad.grpc.management.services.PeerService;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.components.Peer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class PeerClientTest {

    private static Server server;
    private static ManagedChannel channel;
    private final String peerServerHostname = "localhost:2";
    private final VirtualPosition position = VirtualPosition.newBuilder().setX(1).setY(1).setZ(1).build();
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    Peer peer;
    @Mock
    GroupLedger groupLedger;
    @Mock
    PeerClient peerClient;
    private PeerClient testClient;

    @BeforeEach
    public void setup() throws Exception {
        PeerService myService = new PeerService(peer);
        server = ServerBuilder.forPort(8099).addService(myService).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
        testClient = new PeerClient(channel);
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        server.shutdown();
        server.awaitTermination();
        channel.shutdown();
        groupLedger.clearAll();
    }

    @Test
    void testAddPeer() {
        Mockito.when(peer.addGroupStoragePeer(Mockito.any())).thenReturn(true);
        boolean result = testClient.addGroupStoragePeer(peerServerHostname);
        Assertions.assertTrue(result);
    }

    @Test
    void testRemovePeer() {
        Mockito.when(peer.removeGroupStoragePeer(Mockito.anyString())).thenReturn(true);
        boolean result = testClient.removePeer(peerServerHostname);
        Assertions.assertTrue(result);
    }

    @Test
    void testRepairObjects() {
        Mockito.when(peer.repairObjects(Mockito.any())).thenReturn(true);
        ObjectList<String> list = ObjectLists.singleton("1");
        boolean result = testClient.repairObjects(list);
        Assertions.assertTrue(result);
    }

    @Test
    void testRepairObjectsFuture() throws InterruptedException {
        Mockito.when(peer.repairObjects(Mockito.any())).thenReturn(true);
        List<String> list = Collections.singletonList("1");
        boolean result = testClient.repairObjectsFuture(list);
        Assertions.assertTrue(result);
    }

    @Test
    void testHandleSuperPeerLeave() throws Exception {
        Mockito.when(peer.closeSuperPeerClientConnection()).thenReturn(true);
        Mockito.when(peer.assignNewRole()).thenReturn(true);
        boolean result = testClient.handleSuperPeerLeave();
        Assertions.assertTrue(result);
    }

    @Test
    void testHandleSuperPeerLeaveFuture() throws Exception {
        Mockito.when(peer.closeSuperPeerClientConnection()).thenReturn(true);
        Mockito.when(peer.assignNewRole()).thenReturn(true);
        boolean result = testClient.handleSuperPeerLeaveFuture();
        Assertions.assertTrue(result);
    }
}