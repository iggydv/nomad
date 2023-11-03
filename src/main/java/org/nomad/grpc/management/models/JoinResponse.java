package org.nomad.grpc.management.models;

import lombok.Builder;
import lombok.Data;
import org.nomad.grpc.superpeerservice.MultiMapPair;

@Data
@Builder
public class JoinResponse {
    MultiMapPair objectLedger;
    MultiMapPair peerLedger;
}
