package org.nomad.grpc.management;

import com.google.protobuf.Empty;
import io.grpc.internal.testing.StreamRecorder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.config.Config;
import org.nomad.grpc.management.services.PeerService;
import org.nomad.grpc.peerservice.AddPeerRequest;
import org.nomad.grpc.peerservice.AddPeerResponse;
import org.nomad.grpc.peerservice.RemovePeerRequest;
import org.nomad.grpc.peerservice.RemovePeerResponse;
import org.nomad.grpc.peerservice.SuperPeerLeaveResponse;
import org.nomad.pithos.components.Peer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class PeerServerTest {

    @Mock
    Peer peer;

    @Mock
    Config config;

    @InjectMocks
    PeerService myService;

    @Test
    void testAddPeer() throws Exception {
        Mockito.when(peer.addGroupStoragePeer(Mockito.anyString())).thenReturn(true);
        AddPeerRequest request = AddPeerRequest.newBuilder().setHost("localhost:1111").build();
        StreamRecorder<AddPeerResponse> responseObserver = StreamRecorder.create();
        myService.addPeer(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<AddPeerResponse> result = responseObserver.getValues();
        AddPeerResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, AddPeerResponse.newBuilder().setResponse(true).build());
    }

    @Test
    void testRemovePeer() throws Exception {
        Mockito.when(peer.removeGroupStoragePeer(Mockito.anyString())).thenReturn(true);
        RemovePeerRequest request = RemovePeerRequest.newBuilder().setHost("localhost:1111").build();
        StreamRecorder<RemovePeerResponse> responseObserver = StreamRecorder.create();
        myService.removePeer(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<RemovePeerResponse> result = responseObserver.getValues();
        RemovePeerResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, RemovePeerResponse.newBuilder().setResponse(true).build());
    }

    @Test
    void testHandleSuperPeerLeave() throws Exception {
        Mockito.when(peer.closeSuperPeerClientConnection()).thenReturn(true);
        Mockito.when(peer.assignNewRole()).thenReturn(true);
        Empty request = Empty.getDefaultInstance();
        StreamRecorder<SuperPeerLeaveResponse> responseObserver = StreamRecorder.create();
        myService.handleSuperPeerLeave(request, responseObserver);

        if (!responseObserver.awaitCompletion(5, TimeUnit.SECONDS)) {
            Assertions.fail("The call did not terminate in time");
        }

        List<SuperPeerLeaveResponse> result = responseObserver.getValues();
        SuperPeerLeaveResponse response = result.get(0);

        assertNull(responseObserver.getError());
        assertEquals(1, result.size());
        assertEquals(response, SuperPeerLeaveResponse.newBuilder().setResponse(true).build());
    }
}