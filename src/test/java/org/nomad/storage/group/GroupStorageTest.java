package org.nomad.storage.group;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ArrayList;
import it.unimi.dsi.fastutil.objects.ArrayLists;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nomad.config.Config;
import org.nomad.grpc.management.clients.SuperPeerClient;
import org.nomad.grpc.management.servers.GroupStorageServer;
import org.nomad.pithos.components.GroupLedger;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class GroupStorageTest {

    @Mock
    GroupStorageServer groupStorageServer;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    Config config;

    @Mock
    SuperPeerClient superPeerClient;

    @InjectMocks
    GroupStorage groupStorage;

    @AfterEach
    public void close() {
        GroupLedger.getInstance().clearAll();
    }

    @Test
    void updateClients() {
        ArrayList<String> initialList_1 = new ObjectArrayList<>();
        ArrayList<String> updated_1 = new ObjectArrayList<>(Lists.newArrayList("1", "2", "4", "5"));
        groupStorage.setClients(initialList_1);
        groupStorage.updateClients(updated_1);
        assertTrue(updated_1.containsAll(groupStorage.getClients()));

        groupStorage.clearMap();

        ArrayList<String> initialList_2 = new ObjectArrayList<>(Lists.newArrayList("1", "2", "3"));
        ArrayList<String> updated_2 = new ObjectArrayList<>(Lists.newArrayList("1", "2", "4", "5"));
        groupStorage.setClients(initialList_2);
        groupStorage.updateClients(updated_2);
        assertTrue(updated_2.containsAll(groupStorage.getClients()));

        groupStorage.clearMap();

        ArrayList<String> initialList_3 = new ObjectArrayList<>(Lists.newArrayList("localhost:9919", "localhost:9929", "localhost:9939", "localhost:9949", "localhost:9959"));
        ArrayList<String> updated_3 = new ObjectArrayList<>(Lists.newArrayList());
        groupStorage.setClients(initialList_3);
        groupStorage.updateClients(updated_3);
        assertTrue(updated_3.containsAll(groupStorage.getClients()));
    }

    @Test
    void quorum() throws IOException {
        Mockito.when(config.getStorage().getReplicationFactor()).thenReturn(4);
        groupStorage.init(0, superPeerClient);

        //  RF reachable
        ArrayList<String> updated_1 = new ObjectArrayList<>(Lists.newArrayList("1", "2", "4", "5"));
        groupStorage.setClients(updated_1);
        assertTrue(groupStorage.quorum(3));
        assertTrue(groupStorage.quorum(4));
        assertTrue(groupStorage.quorum(2));
        assertFalse(groupStorage.quorum(1));
        assertFalse(groupStorage.quorum(0));
        groupStorage.clearMap();

        // RF unreachable
        ArrayList<String> updated_2 = new ObjectArrayList<>(Lists.newArrayList("1", "2"));
        groupStorage.setClients(updated_2);
        assertFalse(groupStorage.quorum(1));
        assertTrue(groupStorage.quorum(2));
        groupStorage.clearMap();

        ArrayList<String> updated_3 = new ObjectArrayList<>(Lists.newArrayList("1"));
        groupStorage.setClients(updated_3);
        assertTrue(groupStorage.quorum(1));
    }
}