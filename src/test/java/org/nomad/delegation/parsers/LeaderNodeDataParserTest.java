package org.nomad.delegation.parsers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Test;
import org.nomad.delegation.models.LeaderNodeData;
import org.nomad.delegation.models.VoronoiSitePoint;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeaderNodeDataParserTest {

    @Test
    void parseLeaderNodeDataFromPath() {
        LeaderNodeData data = LeaderNodeDataParser.parseLeaderNodeDataFromPath("/GroupStorage/leaders").build();
        assertNull(data.getLeaderId());
        assertNull(data.getGroupName());
        assertNull(data.getHostname());
        LeaderNodeData data_1 = LeaderNodeDataParser.parseLeaderNodeDataFromPath("/GroupStorage/leaders/group-1").build();
        assertEquals("group-1", data_1.getGroupName());
        LeaderNodeData data_2 = LeaderNodeDataParser.parseLeaderNodeDataFromPath("/GroupStorage/leaders/group-1/abc").build();
        assertEquals("abc", data_2.getLeaderId());
        assertEquals("group-1", data_2.getGroupName());
    }

    @Test
    void parseLeaderNodeData() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        VoronoiSitePoint newData = new VoronoiSitePoint("localhost", 0, 0);
        String jsonData = objectMapper.writeValueAsString(newData);
        ChildData testData = new ChildData("/GroupStorage/leaders/group-1/abc", new Stat(), jsonData.getBytes(StandardCharsets.UTF_8));
        LeaderNodeData expected = LeaderNodeData.builder().groupName("group-1").leaderId("abc").hostname("localhost").x(0).y(0).build();
        LeaderNodeData actual = LeaderNodeDataParser.parseLeaderNodeData(testData);

        assertEquals(expected, actual);
    }
}