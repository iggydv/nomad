package org.nomad.config;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(force = true)
public class NetworkHostnames {
    // IP:port combination of the peer gRPC server
    String peerServer;
    // IP:port combination of the peer gRPC server
    String peerStorageServer;
    // IP:port combination of the overlay client
    String overlayServer;
    // IP:port combination of the super-peer gRPC server
    String superPeerServer;
    // IP:port combination of the group-storage gRPC server
    String groupStorageServer;

    @Override
    public String toString() {
        return "Component hostnames:\n" +
                "Peer-Server: " + peerServer + "\n" +
                "Peer-Storage-Server: " + peerStorageServer + "\n" +
                "Overlay-Server: " + overlayServer + "\n" +
                "Super-Peer-Server: " + superPeerServer + "\n" +
                "Group-Storage-Server: " + groupStorageServer;
    }
}
