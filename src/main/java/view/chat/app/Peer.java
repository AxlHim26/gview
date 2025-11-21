package view.chat.app;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class Peer {
    private int port;
    private ServerSocket serverSocket;
    
    // Tách riêng incoming và outgoing connections
    private ConcurrentHashMap<String, PeerConnection> incomingConnections;
    private ConcurrentHashMap<String, PeerConnection> outgoingConnections;
    
    public Peer(int port) {
        this.port = port;
        this.incomingConnections = new ConcurrentHashMap<>();
        this.outgoingConnections = new ConcurrentHashMap<>();
        
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Peer started on port " + port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    // Start server thread
    public void startServer() {
        new Thread(() -> {
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    String peerAddress = clientSocket.getInetAddress()
                        .getHostAddress() + ":" + clientSocket.getPort();
                    
                    System.out.println("Incoming connection from: " + peerAddress);
                    
                    PeerConnection connection = new PeerConnection(
                        clientSocket, peerAddress, true);
                    incomingConnections.put(peerAddress, connection);
                    
                    new ServerHandler(connection).start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
    
    // Connect tới peer khác
    public void connectToPeer(String host, int peerPort) {
        try {
            Socket socket = new Socket(host, peerPort);
            String peerAddress = host + ":" + peerPort;
            
            PeerConnection connection = new PeerConnection(
                socket, peerAddress, false);
            outgoingConnections.put(peerAddress, connection);
            
            new ClientHandler(connection).start();
            System.out.println("Connected to peer at " + peerAddress);
        } catch (IOException e) {
            System.out.println("Failed to connect to " + host + ":" + peerPort);
            e.printStackTrace();
        }
    }
    
    // Gửi message đến peer cụ thể
    public void sendMessageToPeer(String peerAddress, String message) {
        PeerConnection connection = outgoingConnections.get(peerAddress);
        if (connection == null) {
            connection = incomingConnections.get(peerAddress);
        }
        
        if (connection != null) {
            connection.sendMessage(message);
        } else {
            System.out.println("Peer not found: " + peerAddress);
        }
    }
    
    // Broadcast đến tất cả peers
    public void broadcastMessage(String message) {
        System.out.println("Broadcasting to " + 
            (incomingConnections.size() + outgoingConnections.size()) + " peers");
        
        // Broadcast đến outgoing connections
        for (PeerConnection conn : outgoingConnections.values()) {
            conn.sendMessage(message);
        }
        
        // Broadcast đến incoming connections
        for (PeerConnection conn : incomingConnections.values()) {
            conn.sendMessage(message);
        }
    }
    
    // Xóa connection khi peer disconnect
    public void removePeer(String peerAddress, boolean isIncoming) {
        if (isIncoming) {
            incomingConnections.remove(peerAddress);
        } else {
            outgoingConnections.remove(peerAddress);
        }
        System.out.println("Removed peer: " + peerAddress);
    }
    
    // List tất cả connections
    public void listConnections() {
        System.out.println("\n=== Active Connections ===");
        System.out.println("Outgoing (" + outgoingConnections.size() + "):");
        for (String peer : outgoingConnections.keySet()) {
            System.out.println("  -> " + peer);
        }
        
        System.out.println("Incoming (" + incomingConnections.size() + "):");
        for (String peer : incomingConnections.keySet()) {
            System.out.println("  <- " + peer);
        }
    }
}
