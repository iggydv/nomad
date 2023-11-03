package org.nomad.commons;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.PortInUseException;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Random;

public class NetworkUtility {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtility.class);
    private final static String LOCALHOST = "localhost";
    private static final RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
            .withDelay(Duration.ofSeconds(1))
            .handle(Exception.class)
            .withMaxRetries(1);
    private static final RetryPolicy<Object> pingRetryPolicy = new RetryPolicy<>()
            .withDelay(Duration.ofMillis(100))
            .onFailedAttempt(e -> logger.debug("retrying ping"))
            .handle(IOException.class, SocketTimeoutException.class, IllegalStateException.class)
            .withMaxRetries(3);
    private static String IP = "";
    private static String ID = "";
    private static String networkInterface = "eth0";

    public static String getID() {
        return ID;
    }

    public static void setID(String id) {
        ID = id;
    }

    /**
     * Checks to see if a specific port is available.
     *
     * @param port the port to check for availability.
     */
    public static boolean available(int port) {
        try {
            return Failsafe.with(retryPolicy).onFailure((e) -> {
                logger.info("Port Busy: {}", e);
            }).get(() -> checkPort(port));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Selects a random port within a valid port range
     *
     * @param minPortNumber the starting port.
     * @param maxPortNumber the end port.
     * @return a random port within the given range
     */
    public static int randomPort(int minPortNumber, int maxPortNumber) {
        if (minPortNumber >= maxPortNumber) {
            throw new IllegalArgumentException("Min port > Max port!");
        }
        Random r = new Random();
        for (int i = 0; i < 3; i++) {
            int port = r.ints(minPortNumber, (maxPortNumber + 1)).findFirst().getAsInt();
            if (available(port)) {
                return port;
            }
        }
        throw new PortInUseException(-1);
    }

    /**
     * Returns the initialized binding interface
     */
    public static String getNetworkInterface() {
        return networkInterface;
    }

    /**
     * Returns the initialized IPv4 Address
     */
    public static String getIP() {
        if (checkInitialization()) {
            return IP;
        }
        logger.error("Please Initialize the NetworkUtility");
        return LOCALHOST;
    }

    /**
     * Initialize the NetworkUtility by selecting a binding Network interface and IPv4 Address
     */
    public static void init() {
        if (!IP.isEmpty() && !networkInterface.isEmpty()) {
            logger.debug("IP ({}) and Network Interface ({}) already set", IP, networkInterface);
            return;
        }

        try {
            String bindIP = LOCALHOST;
            Enumeration<NetworkInterface> networkInterfaceEnumeration = NetworkInterface.getNetworkInterfaces();

            networkInterfaceLoop:
            while (networkInterfaceEnumeration.hasMoreElements()) {
                NetworkInterface ni = networkInterfaceEnumeration.nextElement();
                String networkInterfaceName = ni.getName();
                // For now don't look at virtual network interfaces
                if (!ni.isVirtual() && ni.isUp() && (networkInterfaceName.startsWith("eth") || networkInterfaceName.startsWith("wlan") || networkInterfaceName.startsWith("en"))) {
                    Enumeration<InetAddress> inetAddressEnumeration = ni.getInetAddresses();
                    while (inetAddressEnumeration.hasMoreElements()) {
                        InetAddress addr = inetAddressEnumeration.nextElement();
                        logger.info("Network InetAddresses, {}: {}", networkInterfaceName, addr.getHostAddress());

                        if (!addr.isLoopbackAddress()) {
                            if (addr instanceof Inet4Address) {
                                bindIP = addr.getHostAddress();
                                networkInterface = networkInterfaceName;
                                break networkInterfaceLoop;
                            }
                        } else {
                            logger.debug("Loopback or IPv6 Address detected, checking other network interfaces");
                        }
                    }
                }
            }

            IP = bindIP.equals(LOCALHOST) ? InetAddress.getLocalHost().getHostAddress() : bindIP;
            logger.info("IP ({}) and Network Interface ({}) set", IP, networkInterface);
        } catch (UnknownHostException | SocketException e) {
            logger.error("Unable to obtain IP address");
        }
    }

    private static boolean checkInitialization() {
        if (!IP.isEmpty()) {
            return true;
        } else {
            logger.error("IP and Network Interface not initialized!");
            return false;
        }
    }

    private static boolean checkPort(int port) throws IOException {
        logger.debug("Checking port availability: {}", port);
        ServerSocket serverSocket = null;
        DatagramSocket datagramSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            datagramSocket = new DatagramSocket(port);
            datagramSocket.setReuseAddress(true);
            logger.debug("Port, {} is available", port);
            return true;
        } catch (IOException e) {
            logger.debug("Port, {} may not be available", port);
            throw new IOException();
        } finally {
            if (datagramSocket != null) {
                datagramSocket.close();
            }

            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    logger.error("Error occurred when closing server socket");
                }
            }
        }
    }

    public static boolean pingClient(String hostname) {
        return pingClient(hostname, 300);
    }

    public static boolean pingClient(String hostname, int timeOutMillis) {
        logger.debug("Executing ping to client to {}", hostname);
        try {
            boolean result = Failsafe.with(pingRetryPolicy)
                    .onFailure(e -> logger.debug("unable to ping {}", hostname))
                    .onComplete(e -> logger.debug("pong received from {}", hostname))
                    .get(() -> isReachable(hostname, timeOutMillis));
            logger.debug("ping succeeded!");
            return result;
        } catch (Exception e) {
            logger.error("ping failed!");
            return false;
        }
    }

    /**
     * https://stackoverflow.com/a/34228756/6941267
     *
     * @param hostname      of the client to ping
     * @param timeOutMillis timeout
     * @return true if socket is reachable
     */
    private static boolean isReachable(String hostname, int timeOutMillis) throws IOException {
        String[] hostnameArray = hostname.split(":");
        String ip = hostnameArray[0];
        int port = Integer.parseInt(hostnameArray[1]);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), timeOutMillis);
        return true;
    }
}
