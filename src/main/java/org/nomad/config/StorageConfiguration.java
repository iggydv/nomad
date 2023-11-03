package org.nomad.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(force = true)
public class StorageConfiguration {
    String mode;
    String storageMode;
    String retrievalMode;
    int replicationFactor;
    double quorum;
}
