package view.chat.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

class PeerConnection {
    private Socket socket;
    private String peerAddress;
    private boolean isIncoming; // true = incoming, false = outgoing
    private PrintWriter out;
    private BufferedReader in;
    private long connectedTime;
    
    public PeerConnection(Socket socket, String peerAddress, 
                          boolean isIncoming) throws IOException {
        this.socket = socket;
        this.peerAddress = peerAddress;
        this.isIncoming = isIncoming;
        this.connectedTime = System.currentTimeMillis();
        
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.in = new BufferedReader(
            new InputStreamReader(socket.getInputStream()));
    }
    
    public void sendMessage(String message) {
        out.println(message);
    }
    
    public String readMessage() throws IOException {
        return in.readLine();
    }
    
    public void close() throws IOException {
        in.close();
        out.close();
        socket.close();
    }
    
    public String getPeerAddress() {
        return peerAddress;
    }
    
    public boolean isIncoming() {
        return isIncoming;
    }
    
    public long getConnectedTime() {
        return connectedTime;
    }
}
