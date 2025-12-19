package com.p2pclient.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;

public class NetworkUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);

    /**
     * Get local non-loopback IP address
     */
    public static String getLocalIPAddress() {
        try {
            String overlayCandidate = null; // Prefer tailscale/headscale (100.x.x.x)
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        // Strongly prefer Tailscale/headscale overlay space (100.x.x.x)
                        if (ip.startsWith("100.")) {
                            logger.info("Using overlay IP {} from interface {}", ip, networkInterface.getName());
                            return ip;
                        }
                        // Next prefer 10.x.x.x for private mesh deployments
                        if (ip.startsWith("10.") && overlayCandidate == null) {
                            overlayCandidate = ip;
                        }
                        if (overlayCandidate == null) {
                            overlayCandidate = ip;
                        }
                    }
                }
            }
            if (overlayCandidate != null) {
                logger.info("Using non-overlay IP candidate {} (no 100.x interface detected)", overlayCandidate);
                return overlayCandidate;
            }
        } catch (SocketException e) {
            logger.error("Error getting local IP address", e);
        }
        
        // Fallback to localhost
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logger.error("Error getting localhost", e);
            return "127.0.0.1";
        }
    }

    /**
     * Find an available port in the specified range
     */
    public static int findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new IllegalStateException("No available port found in range " + startPort + "-" + endPort);
    }

    /**
     * Check if a port is available
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if a peer is reachable
     */
    public static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Validate peer ID format (XXX-XXX-XXX)
     */
    public static boolean isValidPeerId(String peerId) {
        if (peerId == null || peerId.isEmpty()) {
            return false;
        }
        return peerId.matches("\\d{3}-\\d{3}-\\d{3}");
    }
}
