package org.nomad.delegation.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LeaderNodeData {
    String groupName;
    String leaderId;
    String hostname;
    double x;
    double y;
}
