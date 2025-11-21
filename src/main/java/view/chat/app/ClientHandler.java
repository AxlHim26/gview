package view.chat.app;

import java.io.IOException;

class ClientHandler extends Thread {
    private PeerConnection connection;
    
    public ClientHandler(PeerConnection connection) {
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
            System.out.println("Connection lost: " + 
                connection.getPeerAddress());
        }
    }
}


