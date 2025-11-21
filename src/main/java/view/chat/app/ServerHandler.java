package view.chat.app;

import java.io.IOException;

class ServerHandler extends Thread {
    private PeerConnection connection;
    
    public ServerHandler(PeerConnection connection) {
        this.connection = connection;
    }
    
    @Override
    public void run() {
        try {
            String message;
            while ((message = connection.readMessage()) != null) {
                System.out.println("[" + connection.getPeerAddress() + 
                    "] " + message);
            }
        } catch (IOException e) {
            System.out.println("Peer disconnected: " + 
                connection.getPeerAddress());
        } finally {
            try {
                connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

