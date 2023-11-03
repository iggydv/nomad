package org.nomad.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

@Data
@Primary
@Configuration
@NoArgsConstructor(force = true)
@ConfigurationProperties(prefix = "node")
public class Config {
    private String peerID = UUID.randomUUID().toString();
    private Boolean malicious;
    private int maxPeers;
    private GroupConfiguration group;
    private StorageConfiguration storage;
    private DirectoryServerConfiguration directoryServer;
    private NetworkHostnames networkHostnames;
    private WorldConfiguration world;
}