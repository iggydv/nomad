package org.nomad.delegation.parsers;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.nomad.delegation.models.DhtNodeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class DhtNodeDataParser {
    private static final Logger logger = LoggerFactory.getLogger(LeaderNodeDataParser.class);

    public static DhtNodeData.DhtNodeDataBuilder parseDhtNodeDataFromPath(String path) {
        DhtNodeData.DhtNodeDataBuilder nodeDataBuilder = DhtNodeData.builder();
        String[] sections = path.split("/");
        if (sections.length == 4) {
            logger.debug("Group created");
            nodeDataBuilder.groupName(Objects.requireNonNull(sections[3], "group name was empty!"));
        } else if (sections.length == 5) {
            logger.debug("Leader for group {} assigned! {}", sections[4], sections[3]);
            nodeDataBuilder
                    .groupName(Objects.requireNonNull(sections[3], "group name was empty!"))
                    .id(Objects.requireNonNull(sections[4], "ID was empty!"));

            logger.debug("Dht node added: {} -> {}", sections[3], sections[4]);
        }
        return nodeDataBuilder;
    }

    public static DhtNodeData parseDhtNodeData(ChildData data) {
        DhtNodeData.DhtNodeDataBuilder nodeDataBuilder = parseDhtNodeDataFromPath(data.getPath());
        String hostname = new String(data.getData(), StandardCharsets.UTF_8);
        nodeDataBuilder.hostname(Objects.requireNonNull(hostname));
        return nodeDataBuilder.build();
    }
}
