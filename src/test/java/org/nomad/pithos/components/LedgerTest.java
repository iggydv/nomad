package org.nomad.pithos.components;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nomad.pithos.models.MetaData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LedgerTest {
    Ledger ledger = new Ledger();
    long ut = Instant.now().getEpochSecond();
    String TEST_OBJECT_KEY_1 = "key1";
    String TEST_OBJECT_KEY_2 = "key2";

    MetaData testMetaData1 = MetaData.builder().id("peer1").ttl(ut).build();
    MetaData testMetaData2 = MetaData.builder().id("peer2").ttl(ut).build();
    MetaData testMetaData3 = MetaData.builder().id("peer3").ttl(ut + 100L).build();
    MetaData testMetaData4 = MetaData.builder().id("peer4").ttl(ut + 100L).build();
    MetaData testMetaData5 = MetaData.builder().id("peer5").ttl(ut + 100L).build();

    @AfterEach
    void cleanUp() {
        ledger.clear();
    }

    @Test
    void testAbleToAddSingleObjectToLedger() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);

        assertEquals(1, ledger.keySet().size());
        assertTrue(ledger.keySet().contains(TEST_OBJECT_KEY_1));
        assertEquals(TEST_OBJECT_KEY_1, ledger.getFirstMatch(testMetaData1));
    }

    @Test
    void testNoDuplicateKeys() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);

        assertEquals(1, ledger.keySet().size());
        assertEquals(1, ledger.values().size());
        assertTrue(ledger.keySet().contains(TEST_OBJECT_KEY_1));
        assertEquals(TEST_OBJECT_KEY_1, ledger.getFirstMatch(testMetaData1));
    }

    @Test
    void testAbleToAddMultipleObjectsToLedger() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData2);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData3);

        assertTrue(ledger.keySet().contains(TEST_OBJECT_KEY_1));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData1));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData2));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData3));
    }

    @Test
    void testRemovalOfKey() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.removeAll(TEST_OBJECT_KEY_1);

        assertTrue(ledger.keySet().isEmpty());
    }

    @Test
    void testRemovalOfValue() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.removeValue(testMetaData1);

        assertNull(ledger.getFirstMatch(testMetaData1));

        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_2, testMetaData1);
        ledger.removeValue(testMetaData1);

        assertNull(ledger.getFirstMatch(testMetaData1));
    }

    @Test
    void testRemovalOfKeyPair() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_2, testMetaData1);
        ledger.remove(TEST_OBJECT_KEY_1, testMetaData1);

        assertTrue(ledger.containsValue(testMetaData1));
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData1));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_2, testMetaData1));
    }

    @Test
    @DisplayName("Should expire old ledger data")
    void testCleanExpiredObjects() {
        MetaData expired = MetaData.builder().id("33").ttl(1612017373).build();
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_2, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData3);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData4);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData5);
        ledger.add(TEST_OBJECT_KEY_1, expired);
        ledger.cleanExpiredObjects();
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData1));
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_2, testMetaData1));
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_1, expired));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData3));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData4));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData5));
    }

    @Test
    @DisplayName("Should remove data from value by id")
    void testRemoveFromValueId() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_2, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData3);
        ledger.removeValueNoTTL("peer1");
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData1));
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_2, testMetaData1));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, testMetaData3));
        assertTrue(ledger.containsEntry(TEST_OBJECT_KEY_1, "peer3"));
        assertFalse(ledger.containsEntry(TEST_OBJECT_KEY_1, "123"));
    }

    @Test
    @DisplayName("Should count object replicas")
    void testCountReplicas() {
        ledger.add(TEST_OBJECT_KEY_1, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_2, testMetaData1);
        ledger.add(TEST_OBJECT_KEY_1, testMetaData3);
        ledger.countReplicas("key1");
        assertEquals(2, ledger.countReplicas("key1"));
        ledger.removeValueNoTTL("peer1");
        assertEquals(1, ledger.countReplicas("key1"));
    }

    @Test
    @DisplayName("Should send a list of objects to be repaired")
    void testRepair() {
        ledger.add("obj1", testMetaData3);
        ledger.add("obj1", testMetaData4);
        ledger.add("obj1", testMetaData5);

        ledger.add("obj2", testMetaData3);
        ledger.add("obj2", testMetaData4);

        List<String> expected_1 = new ArrayList<>(Arrays.asList(
                "obj1",
                "obj2"
        ));
        List<String> expected_2 = new ArrayList<>(Collections.singletonList(
                "obj2"
        ));

        List<String> result_1 = ledger.getObjectsNeedingRepair(5);
        assertTrue(expected_1.containsAll(result_1));
        assertEquals(result_1.size(), expected_1.size());

        List<String> result_2 = ledger.getObjectsNeedingRepair(3);
        assertTrue(expected_2.containsAll(result_2));
        assertEquals(result_2.size(), expected_2.size());

        List<String> result_3 = ledger.getObjectsNeedingRepair(1);
        assertTrue(new ArrayList<>().containsAll(result_3));
        assertEquals(result_3.size(), 0);
    }

    @Test
    @DisplayName("contains key value (no TTL)")
    void testContainsEntryNoTTL() {
        ledger.add("obj1", testMetaData3);
        ledger.add("obj1", testMetaData4);
        ledger.add("obj1", testMetaData5);
        ledger.add("obj2", testMetaData3);
        ledger.add("obj2", testMetaData4);

        assertTrue(ledger.containsEntryNoTTL("obj1", "peer3"));
        assertTrue(ledger.containsEntryNoTTL("obj1", "peer4"));
        assertTrue(ledger.containsEntryNoTTL("obj1", "peer5"));
        assertTrue(ledger.containsEntryNoTTL("obj2", "peer3"));
        assertTrue(ledger.containsEntryNoTTL("obj2", "peer4"));
        assertFalse(ledger.containsEntryNoTTL("obj2", "peer1"));
    }
}
