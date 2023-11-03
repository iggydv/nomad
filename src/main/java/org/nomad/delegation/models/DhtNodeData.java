package org.nomad.delegation.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DhtNodeData {
    String groupName;
    String id;
    String hostname;
}
