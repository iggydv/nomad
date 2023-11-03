package org.nomad.delegation.parsers;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Test;
import org.nomad.delegation.models.DhtNodeData;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DhtNodeDataParserTest {

    @Test
    void parseDhtNodeDataFromPath() {
        DhtNodeData data = DhtNodeDataParser.parseDhtNodeDataFromPath("/GroupStorage/dht").build();
        assertNull(data.getId());
        assertNull(data.getGroupName());
        assertNull(data.getHostname());
        DhtNodeData data_1 = DhtNodeDataParser.parseDhtNodeDataFromPath("/GroupStorage/dht/group-1").build();
        assertEquals("group-1", data_1.getGroupName());
        DhtNodeData data_2 = DhtNodeDataParser.parseDhtNodeDataFromPath("/GroupStorage/dht/group-1/abc").build();
        assertEquals("abc", data_2.getId());
        assertEquals("group-1", data_2.getGroupName());
    }

    @Test
    void parseDhtNodeData() {
        ChildData testData = new ChildData("/GroupStorage/leaders/group-1/abc", new Stat(), "localhost".getBytes(StandardCharsets.UTF_8));
        DhtNodeData expected = DhtNodeData.builder().groupName("group-1").id("abc").hostname("localhost").build();

        DhtNodeData actual = DhtNodeDataParser.parseDhtNodeData(testData);

        assertEquals(expected, actual);
    }
}