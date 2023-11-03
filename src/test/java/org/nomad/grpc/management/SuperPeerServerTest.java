package org.nomad.grpc.management;

import io.grpc.internal.testing.StreamRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.config.Config;
import org.nomad.grpc.management.services.SuperPeerService;
import org.nomad.grpc.superpeerservice.*;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.components.SuperPeer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class SuperPeerServerTest {

    @Mock
    Config configuration;

    @Mock
    SuperPeer superPeer;

    @InjectMocks
    SuperPeerService myService;

    GroupLedger groupLedger = GroupLedger.getInstance();

    @AfterEach
    public void clean() {
        superPeer.clearLedger();
    }

    @Test
    void testHandleJoin() throws Exception {
        groupLedger.clearAll();
        Mockito.when(superPeer.handleJoin(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        JoinRequest request = JoinRequest.newBuilder()
                .setPeerServerHost("localhost:1111")
                .setGroupStorageServerHost("localhost:1112")
                .build();

        StreamRecorder<JoinResponseWithLedger> responseObserver = StreamRecorder.create();
        myService.handleJoin(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<JoinResponseWithLedger> result = responseObserver.getValues();
        JoinResponseWithLedger response = result.get(0);
        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(JoinResponseWithLedger.newBuilder()
                .setPeerLedger(MultiMapPair.getDefaultInstance())
                .setObjectLedger(MultiMapPair.getDefaultInstance())
                .setAccepted(true)
                .build(), response);
    }

    @Test
    void testHandleLeave() throws Exception {
        LeaveRequest request = LeaveRequest.newBuilder()
                .setPeerServerHostname("localhost:1111")
                .setGroupStorageServerHostname("localhost:1112")
                .build();

        StreamRecorder<LeaveResponse> responseObserver = StreamRecorder.create();
        myService.handleLeave(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<LeaveResponse> result = responseObserver.getValues();
        LeaveResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(LeaveResponse.newBuilder().getDefaultInstanceForType(), response);
    }

    @Test
    void testHandleLeaveForPings() throws Exception {
        // Peer server will not be set, so we need to ensure that the peer is still removed from the peer and the super-peer
        LeaveRequest request = LeaveRequest.newBuilder()
                .setGroupStorageServerHostname("localhost:1112")
                .build();

        StreamRecorder<LeaveResponse> responseObserver = StreamRecorder.create();
        myService.handleLeave(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<LeaveResponse> result = responseObserver.getValues();
        LeaveResponse response = result.get(0);

        System.out.println(response);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertFalse(response.getSuccess());
        assertEquals(LeaveResponse.newBuilder().getDefaultInstanceForType(), response);
    }

    @Test
    void testHandleLeaveFlow() throws Exception {
        Mockito.when(superPeer.handleJoin(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.when(superPeer.handleLeave(Mockito.anyString(), Mockito.anyString())).thenReturn(true);

        JoinRequest request = JoinRequest.newBuilder()
                .setPeerServerHost("localhost:1111")
                .setGroupStorageServerHost("localhost:1112")
                .build();

        // assume join succeeds
        myService.handleJoin(request, StreamRecorder.create());

        // Peer server will not be set, so we need to ensure that the peer is still removed from the peer and the super-peer
        LeaveRequest leaveRequest = LeaveRequest.newBuilder()
                .setPeerServerHostname("localhost:1111")
                .setGroupStorageServerHostname("localhost:1112")
                .build();

        StreamRecorder<LeaveResponse> leaveResponseObserver = StreamRecorder.create();
        myService.handleLeave(leaveRequest, leaveResponseObserver);

        if (!leaveResponseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<LeaveResponse> leaveResponses = leaveResponseObserver.getValues();
        LeaveResponse response = leaveResponses.get(0);

        System.out.println(response);

        assertNull(leaveResponseObserver.getError());
        assertEquals(1, leaveResponses.size());
        assertTrue(response.getSuccess());
        assertEquals(LeaveResponse.newBuilder().setSuccess(true).build(), response);
    }

    @Test
    void testHandleLeaveFlowForPings() throws Exception {
        Mockito.when(superPeer.handleJoin(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        Mockito.when(superPeer.handleLeave(Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        JoinRequest request = JoinRequest.newBuilder()
                .setPeerServerHost("localhost:1111")
                .setGroupStorageServerHost("localhost:1112")
                .build();

        // assume join succeeds
        myService.handleJoin(request, StreamRecorder.create());

        // Peer server will not be set, so we need to ensure that the peer is still removed from the peer and the super-peer
        LeaveRequest leaveRequest = LeaveRequest.newBuilder()
                .setGroupStorageServerHostname("localhost:1112")
                .build();

        StreamRecorder<LeaveResponse> leaveResponseObserver = StreamRecorder.create();
        myService.handleLeave(leaveRequest, leaveResponseObserver);

        if (!leaveResponseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<LeaveResponse> leaveResponses = leaveResponseObserver.getValues();
        LeaveResponse response = leaveResponses.get(0);

        System.out.println(response);

        assertNull(leaveResponseObserver.getError());
        assertEquals(1, leaveResponses.size());
        assertTrue(response.getSuccess());
        assertEquals(LeaveResponse.newBuilder().setSuccess(true).build(), response);
    }

    @Test
    void testRepair() throws Exception {
        RepairObjectRequest request = RepairObjectRequest.newBuilder().setObjectId("1").build();
        StreamRecorder<RepairObjectResponse> responseObserver = StreamRecorder.create();
        myService.repair(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<RepairObjectResponse> result = responseObserver.getValues();
        RepairObjectResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, RepairObjectResponse.newBuilder().setSucceed(true).build());
    }

    @Test
    void testMigrate() throws Exception {
        Mockito.when(superPeer.migrate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(true);
        MigrationRequest request = MigrationRequest.newBuilder().setNewSuperPeerId("2").setPeerId("1").build();
        StreamRecorder<MigrationResponse> responseObserver = StreamRecorder.create();
        myService.migrate(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<MigrationResponse> result = responseObserver.getValues();
        MigrationResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, MigrationResponse.newBuilder().setSucceed(true).build());
    }

    @Test
    void testAddObjectReference() throws Exception {
        AddObjectRequest request = AddObjectRequest.newBuilder().setObjectId("1").setPeerId("1").setTtl(1).build();
        Mockito.when(superPeer.addObjectReference(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(true);
        StreamRecorder<AddObjectResponse> responseObserver = StreamRecorder.create();
        myService.addObjectReference(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<AddObjectResponse> result = responseObserver.getValues();
        AddObjectResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, AddObjectResponse.newBuilder().setSucceed(true).build());
        GroupLedger.getInstance().cleanExpiredObjects();
    }

    @Test
    void testPingPeer() throws Exception {
        PingRequest request = PingRequest.newBuilder().setHostname("localhost:1111").build();
        StreamRecorder<PingResponse> responseObserver = StreamRecorder.create();
        myService.pingPeer(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<PingResponse> result = responseObserver.getValues();
        PingResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, PingResponse.newBuilder().setResult(false).build());
    }

    @Test
    void testNotifyPeers() throws Exception {
        Mockito.when(superPeer.notifyPeers(Mockito.anyString())).thenReturn(true);
        NotifyPeersRequest request = NotifyPeersRequest.newBuilder().setHost("localhost:1111").build();
        StreamRecorder<NotifyPeersResponse> responseObserver = StreamRecorder.create();
        myService.notifyPeers(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<NotifyPeersResponse> result = responseObserver.getValues();
        NotifyPeersResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, NotifyPeersResponse.newBuilder().setResult(true).build());
    }
}