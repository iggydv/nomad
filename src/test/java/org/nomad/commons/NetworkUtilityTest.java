package org.nomad.commons;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Description;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkUtilityTest {
    @Test
    @Description("Correctly detects if a port is unavailable or outside specified port range")
    void available() throws IOException {

        ServerSocket serverSocket = new ServerSocket(6000);
        serverSocket.setReuseAddress(true);

        assertTrue(NetworkUtility.available(6001));
        assertFalse(NetworkUtility.available(6000));
    }

    @Test
    @Description("Generated port falls within specified port range")
    void randomPort() {
        int minPort = 4000;
        int maxPort = 7000;

        int randomPort = NetworkUtility.randomPort(minPort, maxPort);
        assert randomPort > minPort;
        assert randomPort < maxPort;
    }

    @Test
    @Description("Ping test")
    void pingClient() throws IOException {

        ServerSocket serverSocket = new ServerSocket(7321);
        serverSocket.setReuseAddress(true);

        assertTrue(NetworkUtility.pingClient("localhost:" + 7321, 50));
        assertFalse(NetworkUtility.pingClient("localhost:" + 7322, 50));
    }
}