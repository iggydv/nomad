package org.nomad.delegation.parsers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.nomad.delegation.models.LeaderNodeData;
import org.nomad.delegation.models.VoronoiSitePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class LeaderNodeDataParser {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(LeaderNodeDataParser.class);

    public static LeaderNodeData.LeaderNodeDataBuilder parseLeaderNodeDataFromPath(String path) {
        LeaderNodeData.LeaderNodeDataBuilder nodeDataBuilder = LeaderNodeData.builder();
        String[] sections = path.split("/");
        if (sections.length == 4) {
            logger.debug("Group created");
            nodeDataBuilder.groupName(Objects.requireNonNull(sections[3], "group name was empty!"));
        } else if (sections.length == 5) {
            logger.debug("Leader for group {} assigned! {}", sections[4], sections[3]);
            nodeDataBuilder.groupName(Objects.requireNonNull(sections[3], "group name was empty!"))
                    .leaderId(Objects.requireNonNull(sections[4], "Leader ID was empty!"));
            logger.info("Leader added: {} -> {}", sections[3], sections[4]);
        }
        return nodeDataBuilder;
    }

    public static LeaderNodeData parseLeaderNodeData(ChildData data) {
        LeaderNodeData.LeaderNodeDataBuilder nodeDataBuilder = parseLeaderNodeDataFromPath(data.getPath());

        String dataString = new String(data.getData(), StandardCharsets.UTF_8);
        if (dataString.contains("hostname")) {
            try {
                VoronoiSitePoint convertedData = objectMapper.readValue(data.getData(), VoronoiSitePoint.class);
                logger.debug("Data updated (hostname + location)");
                nodeDataBuilder.hostname(Objects.requireNonNull(convertedData.getHostname()));
                nodeDataBuilder.x(convertedData.getX());
                nodeDataBuilder.y(convertedData.getY());
            } catch (IOException e) {
                logger.error("Failed to map data to Voronoi Site point");
//                e.printStackTrace();
            }
        } else {
            logger.debug("Data updated (hostname)");
            nodeDataBuilder.hostname(Objects.requireNonNull(dataString));
        }
        return nodeDataBuilder.build();
    }
}
