package org.nomad.grpc.management;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.delegation.models.NeighbourData;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.models.JoinResponse;
import org.nomad.grpc.management.models.UpdatePeerPositionResponse;
import org.nomad.grpc.management.services.SuperPeerService;
import org.nomad.grpc.superpeerservice.MetaDataCollection;
import org.nomad.grpc.superpeerservice.MultiMapPair;
import org.nomad.grpc.superpeerservice.VirtualPosition;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.components.SuperPeer;
import org.nomad.pithos.models.MetaData;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class SuperPeerClientTest {

    private static Server server;
    private static ManagedChannel channel;
    private final String groupStorageServerHostname = "localhost:1";
    private final String peerServerHostname = "localhost:2";
    private final VirtualPosition position = VirtualPosition.newBuilder().setX(1).setY(1).setZ(1).build();
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    SuperPeer superPeer;
    @Mock
    GroupLedger groupLedger;
    @Mock
    SuperPeerClient superPeerClient;
    private SuperPeerClient testClient;
    private SuperPeerService myService;

    @BeforeEach
    public void setup() throws Exception {
        myService = new SuperPeerService(superPeer);
        server = ServerBuilder.forPort(8099).addService(myService).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().keepAliveTime(300, TimeUnit.SECONDS).build();
        testClient = new SuperPeerClient(channel);
        groupLedger = GroupLedger.getInstance();
    }

    @AfterEach
    public void stopServer() throws InterruptedException {
        server.shutdown();
        server.awaitTermination();
        channel.shutdown();
        groupLedger.clearAll();
    }

    @Test
    void testJoinGroup() throws IOException {
        groupLedger.clearAll();
        myService.clearLedger();
        Mockito.when(superPeer.handleJoin(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        JoinResponse result = testClient.joinGroup(peerServerHostname, groupStorageServerHostname, position);

        Assertions.assertEquals(MultiMapPair.getDefaultInstance(), result.getObjectLedger());
        Assertions.assertEquals(MultiMapPair.getDefaultInstance(), result.getPeerLedger());
    }

    @Test
    void testJoinGroupWithData() throws IOException {
        groupLedger.clearAll();
        myService.clearLedger();

        groupLedger.addToObjectLedger("1", MetaData.builder().id("abc123").ttl(1897744637L).build());
        groupLedger.addToPeerLedger("abc123", MetaData.builder().id("1").ttl(1897744637L).build());

        Mockito.when(superPeer.handleJoin(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        JoinResponse result = testClient.joinGroup(peerServerHostname, groupStorageServerHostname, position);
        MultiMapPair expectedObjectLedger = MultiMapPair.newBuilder()
                .putKeyPair("1", MetaDataCollection.newBuilder()
                        .addValues(org.nomad.grpc.superpeerservice.MetaData.newBuilder()
                                .setId("abc123")
                                .setTtl(1897744637L)
                                .build())
                        .build())
                .build();

        MultiMapPair expectedPeerLedger = MultiMapPair.newBuilder()
                .putKeyPair("abc123", MetaDataCollection.newBuilder()
                        .addValues(org.nomad.grpc.superpeerservice.MetaData.newBuilder()
                                .setId("1")
                                .setTtl(1897744637L)
                                .build())
                        .build())
                .build();

        Assertions.assertEquals(expectedObjectLedger, result.getObjectLedger());
        Assertions.assertEquals(expectedPeerLedger, result.getPeerLedger());
    }

    @Test
    void testLeaveGroup() {
        Mockito.when(superPeer.handleLeave(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        boolean result = testClient.leaveGroup(peerServerHostname, groupStorageServerHostname);
        Assertions.assertTrue(result);
    }

    @Test
    void testNotifyPeers() {
        Mockito.when(superPeer.notifyPeers(Mockito.anyString())).thenReturn(true);
        boolean result = testClient.notifyPeers(groupStorageServerHostname);
        Assertions.assertTrue(result);
    }

    @Test
    void testPingPeer() {
        Mockito.when(superPeer.pingPeer(Mockito.anyString())).thenReturn(true);
        boolean result = testClient.pingPeer(peerServerHostname);
        Assertions.assertTrue(result);
    }

    @Test
    void testAddObjectReference() throws InterruptedException {
        Mockito.when(superPeer.addObjectReference(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(true);
        boolean result = testClient.addObjectReference("1", peerServerHostname, 60);
        Assertions.assertTrue(result);
    }


    @Test
    void testRemovePeerGroupLedger() throws InterruptedException {
        Mockito.when(superPeer.removePeerGroupLedger(Mockito.anyString())).thenReturn(true);
        boolean result = testClient.removePeerGroupLedger(peerServerHostname);
        Assertions.assertTrue(result);
    }

    @Test
    void testUpdatePeerPosition() throws Exception {
        Mockito.when(superPeer.getNeighbourSuperPeer(Mockito.any())).thenReturn(NeighbourData.defaultInstance());
        Mockito.when(superPeer.isWithinAOI(Mockito.any())).thenReturn(true);
        UpdatePeerPositionResponse result = testClient.updatePeerPosition(peerServerHostname, position);
        Assertions.assertTrue(result.isAck());
        Assertions.assertEquals("", result.getNewSuperPeer());
        Assertions.assertEquals("", result.getNewGroup());
    }

    @Test
    void testUpdatePeerPositionFuture() throws Exception {
        Mockito.when(superPeer.getNeighbourSuperPeer(Mockito.any())).thenReturn(NeighbourData.defaultInstance());
        Mockito.when(superPeer.isWithinAOI(Mockito.any())).thenReturn(true);
        UpdatePeerPositionResponse result = testClient.updatePeerPositionFuture(peerServerHostname, position);
        Assertions.assertTrue(result.isAck());
        Assertions.assertEquals("", result.getNewSuperPeer());
        Assertions.assertEquals("", result.getNewGroup());
    }

}