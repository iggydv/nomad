package org.nomad.pithos.models;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MetaData {
    String id;
    long ttl; // unix timestamp
}
