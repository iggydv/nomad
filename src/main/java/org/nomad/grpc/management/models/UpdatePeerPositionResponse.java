package org.nomad.grpc.management.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdatePeerPositionResponse {
    boolean ack;
    String newSuperPeer;
    String newGroup;
}