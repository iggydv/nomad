package org.nomad.storage;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.config.Config;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.pithos.components.GroupLedger;
import org.nomad.pithos.models.GameObject;
import org.nomad.storage.group.GroupStorage;
import org.nomad.storage.local.LocalStorage;
import org.nomad.storage.overlay.DHTOverlayStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.Future;

@EnableConfigurationProperties(value = Config.class)
@TestPropertySource("classpath:application.yml")
@ExtendWith(MockitoExtension.class)
class PeerStorageTest {
    long unixTime = Instant.now().getEpochSecond();
    private final GameObject testObject = GameObject.builder()
            .id("0")
            .value("0".getBytes(StandardCharsets.UTF_8))
            .creationTime(unixTime)
            .ttl(unixTime + 600L)
            .build();
    @Mock
    private LocalStorage localStorage;
    @Mock
    private DHTOverlayStorage dhtOverlayStorage;
    @Mock
    private GroupStorage groupStorage;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Config configuration;
    @Mock
    private GroupLedger groupLedger;
    @InjectMocks
    private PeerStorage peerStorage;

    @BeforeEach
    public void setup() {
        peerStorage.setGroupLedger(groupLedger);
    }

    @Test
    void init() throws Exception {
        Mockito.when(configuration.getStorage().getRetrievalMode()).thenReturn("fast");
        Mockito.when(configuration.getStorage().getStorageMode()).thenReturn("fast");
        SuperPeerClient superPeerClient = Mockito.mock(SuperPeerClient.class);
        peerStorage.init(8080, superPeerClient);
    }

    @Test
    void get_overlay_1() throws Exception {
        Mockito.lenient().when(groupLedger.objectLedgerContainsKey(Mockito.anyString())).thenReturn(false);
        Mockito.lenient().when(dhtOverlayStorage.get(Mockito.anyString())).thenReturn(testObject);
        Assertions.assertEquals(testObject, peerStorage.get("2", true, true));
    }

    @Test
    void get_overlay_2() throws Exception {
        Mockito.when(groupLedger.objectLedgerContainsKey(Mockito.anyString())).thenReturn(true);
        Mockito.when(configuration.getStorage().getRetrievalMode()).thenReturn("fast");
        Mockito.when(configuration.getStorage().getStorageMode()).thenReturn("fast");

        Mockito.lenient().when(dhtOverlayStorage.get(Mockito.anyString())).thenReturn(testObject);
        Mockito.lenient().when(groupStorage.fastGet(Mockito.anyString())).thenThrow(NoSuchElementException.class);
        Mockito.lenient().when(localStorage.get(Mockito.anyString())).thenThrow(NoSuchElementException.class);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 80).usePlaintext().build();
        peerStorage.init(90, new SuperPeerClient(channel));

        Assertions.assertEquals(testObject, peerStorage.get("2", true, true));
    }

    @Test
    void get_group_storage() throws Exception {
        Mockito.when(groupLedger.objectLedgerContainsKey(Mockito.anyString())).thenReturn(true);
        Mockito.when(configuration.getStorage().getRetrievalMode()).thenReturn("fast");
        Mockito.when(configuration.getStorage().getStorageMode()).thenReturn("fast");

        Mockito.lenient().when(dhtOverlayStorage.get(Mockito.anyString())).thenThrow(NoSuchElementException.class);
        Mockito.lenient().when(localStorage.get(Mockito.anyString())).thenThrow(NoSuchElementException.class);
        Mockito.lenient().when(groupStorage.fastGet(Mockito.anyString())).thenReturn(testObject);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 80).usePlaintext().build();
        peerStorage.init(90, new SuperPeerClient(channel));

        Assertions.assertEquals(testObject, peerStorage.get("2", true, true));
    }

    @Test
    void get_local_storage() throws InterruptedException {
        Mockito.when(groupLedger.objectLedgerContainsKey(Mockito.anyString())).thenReturn(true);
        Mockito.lenient().when(localStorage.get(Mockito.anyString())).thenReturn(testObject);

        Assertions.assertEquals(testObject, peerStorage.get("2", true, true));
    }

    @Test
    void put_all_pass() throws Exception {
        Mockito.when(localStorage.put(Mockito.any())).thenReturn(true);
        Mockito.when(configuration.getStorage().getRetrievalMode()).thenReturn("fast");
        Mockito.when(configuration.getStorage().getStorageMode()).thenReturn("fast");
        Mockito.when(configuration.getNetworkHostnames().getGroupStorageServer()).thenReturn("localhost:90");
        Mockito.when(groupLedger.thisPeerContainsObject(Mockito.any(), Mockito.any())).thenReturn(false);

        Mockito.lenient().when(dhtOverlayStorage.put(Mockito.any())).thenReturn(true);
        Mockito.lenient().when(localStorage.put(Mockito.any())).thenReturn(true);
        Mockito.lenient().when(groupStorage.fastPut(Mockito.any())).thenReturn(true);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 80).usePlaintext().build();
        peerStorage.init(90, new SuperPeerClient(channel));

        Assertions.assertTrue(peerStorage.put(testObject, true, true));
    }

    @Test
    void put_some_pass() throws Exception {
        Mockito.when(configuration.getStorage().getRetrievalMode()).thenReturn("fast");
        Mockito.when(configuration.getStorage().getStorageMode()).thenReturn("fast");
        Mockito.when(configuration.getNetworkHostnames().getGroupStorageServer()).thenReturn("localhost:90");
        Mockito.when(groupLedger.thisPeerContainsObject(Mockito.any(), Mockito.any())).thenReturn(false);

        Mockito.lenient().when(dhtOverlayStorage.put(Mockito.any())).thenReturn(true);
        Mockito.lenient().when(localStorage.put(Mockito.any())).thenReturn(false);
        Mockito.lenient().when(groupStorage.fastPut(Mockito.any())).thenReturn(true);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 80).usePlaintext().build();
        peerStorage.init(90, new SuperPeerClient(channel));

        Assertions.assertFalse(peerStorage.put(testObject, true, true));
    }

    @Test
    void update() throws Exception {
        Mockito.when(configuration.getStorage().getRetrievalMode()).thenReturn("fast");
        Mockito.when(configuration.getStorage().getStorageMode()).thenReturn("fast");
        Mockito.when(groupLedger.objectLedgerContainsKey(Mockito.anyString())).thenReturn(true);

        Mockito.lenient().when(dhtOverlayStorage.update(Mockito.any())).thenReturn(true);
        Mockito.lenient().when(localStorage.update(Mockito.any())).thenReturn(true);
        Mockito.lenient().when(groupStorage.safeUpdate(Mockito.any())).thenReturn(true);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 80).usePlaintext().build();
        peerStorage.init(90, new SuperPeerClient(channel));

        Assertions.assertTrue(peerStorage.update(testObject));
    }

    @Test
    void delete() throws IOException, InterruptedException {
        // Not really used, so not required to test
        Mockito.lenient().when(dhtOverlayStorage.delete(Mockito.anyString())).thenReturn(true);
        Mockito.lenient().when(localStorage.delete(Mockito.anyString())).thenReturn(true);
        Mockito.lenient().when(groupStorage.safeDelete(Mockito.anyString())).thenReturn(true);

        Assertions.assertTrue(peerStorage.delete("2"));
    }

    @Test
    void localPut_contained() throws InterruptedException {
        Mockito.when(groupLedger.thisPeerContainsObject(Mockito.any(), Mockito.any())).thenReturn(true);
        Assertions.assertFalse(peerStorage.localPut(testObject));
    }

    @Test
    void localPut_not_contained() throws InterruptedException {
        Mockito.lenient().when(localStorage.put(Mockito.any())).thenReturn(true);
        Mockito.when(groupLedger.thisPeerContainsObject(Mockito.any(), Mockito.any())).thenReturn(false);

        Assertions.assertTrue(peerStorage.localPut(testObject));
        Mockito.verify(groupStorage, Mockito.times(1)).notifyAllPeersObjectAdded(Mockito.anyString(), Mockito.anyLong());
    }

    @Test
    void localPut_fails() throws InterruptedException {
        Mockito.lenient().when(localStorage.put(Mockito.any())).thenReturn(false);
        Mockito.when(groupLedger.thisPeerContainsObject(Mockito.any(), Mockito.any())).thenReturn(false);

        Assertions.assertFalse(peerStorage.localPut(testObject));
    }

    @Test
    void finalQuorum() throws InterruptedException {
        Mockito.lenient().when(localStorage.put(Mockito.any())).thenReturn(false);

        GameObject testObject1 = GameObject.builder()
                .id("1")
                .value("hello-there-1".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(unixTime + 600L)
                .build();

        GameObject testObject2 = GameObject.builder()
                .id("1")
                .value("hello-there-2".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(unixTime + 600L)
                .build();

        GameObject testObject3 = GameObject.builder()
                .id("1")
                .value("hello-there-3".getBytes(StandardCharsets.UTF_8))
                .creationTime(unixTime)
                .ttl(unixTime + 600L)
                .build();

        Future<GameObject> g1 = ConcurrentUtils.constantFuture(testObject1);
        Future<GameObject> g2 = ConcurrentUtils.constantFuture(testObject2);
        Future<GameObject> g3 = ConcurrentUtils.constantFuture(testObject3);

        ArrayList<Future<GameObject>> list = new ObjectArrayList<>(Arrays.asList(g1, g1, g1));
        GameObject finalGameObject = peerStorage.finalQuorum(list);
        Assertions.assertEquals(testObject1, finalGameObject);

        ArrayList<Future<GameObject>> list1 = new ObjectArrayList<>(Arrays.asList(g1, g1, g2));
        GameObject finalGameObject1 = peerStorage.finalQuorum(list1);
        Assertions.assertEquals(testObject1, finalGameObject1);

        ArrayList<Future<GameObject>> list2 = new ObjectArrayList<>(Arrays.asList(g1, g3, g2));
        GameObject finalGameObject2 = peerStorage.finalQuorum(list2);
        Assertions.assertNull(finalGameObject2);
    }
}