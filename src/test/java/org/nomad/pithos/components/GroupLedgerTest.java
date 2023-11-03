package org.nomad.pithos.components;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nomad.pithos.models.MetaData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupLedgerTest {

    GroupLedger ledger = GroupLedger.getInstance();
    long ut = Instant.now().getEpochSecond();
    MetaData testMetaData3 = MetaData.builder().id("peer3").ttl(ut + 100L).build();
    MetaData testMetaData4 = MetaData.builder().id("peer4").ttl(ut + 100L).build();
    MetaData testMetaData5 = MetaData.builder().id("peer5").ttl(ut + 100L).build();

    @Test
    @DisplayName("Should send a list of objects to be repaired")
    void testRepair() {
        ledger.addToObjectLedger("obj1", testMetaData3);
        ledger.addToObjectLedger("obj1", testMetaData4);
        ledger.addToObjectLedger("obj1", testMetaData5);

        ledger.addToObjectLedger("obj2", testMetaData3);
        ledger.addToObjectLedger("obj2", testMetaData4);

        Object2ObjectOpenHashMap<String, Integer> expected_map_1 = new Object2ObjectOpenHashMap<>();
        expected_map_1.put("obj1", 2);
        expected_map_1.put("obj2", 3);

        Object2ObjectOpenHashMap<String, Integer> expected_map_2 = new Object2ObjectOpenHashMap<>();
        expected_map_2.put("obj2", 1);

        Object2ObjectOpenHashMap<String, Integer> result_1 = ledger.objectsThatNeedRepair(5);
        assertTrue(expected_map_1.keySet().containsAll(result_1.keySet()));
        assertEquals(expected_map_1.size(), result_1.size());
        assertEquals(2, result_1.get("obj1"));
        assertEquals(3, result_1.get("obj2"));

        Object2ObjectOpenHashMap<String, Integer> result_2 = ledger.objectsThatNeedRepair(3);
        assertTrue(expected_map_2.keySet().containsAll(result_2.keySet()));
        assertEquals(result_2.size(), result_2.size());
        assertEquals(1, result_2.get("obj2"));

        Object2ObjectOpenHashMap<String, Integer> result_3 = ledger.objectsThatNeedRepair(2);
        assertTrue(new ArrayList<>().containsAll(result_3.keySet()));
        assertEquals(0, result_3.size());

        Object2ObjectOpenHashMap<String, Integer> result_4 = ledger.objectsThatNeedRepair(1);
        assertTrue(new ArrayList<>().containsAll(result_4.keySet()));
        assertEquals(0, result_4.size());
    }

    @Test
    @DisplayName("Should filter keys already containing a value")
    void filterPeersStoringObject() {
        ledger.addToPeerLedger("obj1", testMetaData3);
        ledger.addToPeerLedger("obj2", testMetaData3);
        ledger.addToPeerLedger("obj3", testMetaData4);
        List<String> keys = new ArrayList<>();
        keys.add("obj1");
        keys.add("obj2");
        keys.add("obj3");

        List<String> result = ledger.removePeersStoringObject(keys, "peer3");
        List<String> result2 = ledger.removePeersNotStoringObject(keys, "peer3");
        List<String> expected = new ArrayList<>(Collections.singletonList("obj3"));
        List<String> expected2 = Arrays.asList("obj1", "obj2");
        assertArrayEquals(expected.toArray(), result.toArray());
        assertArrayEquals(expected2.toArray(), result2.toArray());
    }
}