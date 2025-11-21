package view.chat.app;

import java.util.Scanner;

public class PeerChatApp {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        
        System.out.print("Enter your port: ");
        int myPort = scanner.nextInt();
        scanner.nextLine();
        
        Peer peer = new Peer(myPort);
        peer.startServer();
        
        while (true) {
            System.out.println("\n=== P2P Chat Menu ===");
            System.out.println("1. Connect to peer");
            System.out.println("2. Send to specific peer");
            System.out.println("3. Broadcast to all");
            System.out.println("4. List connections");
            System.out.println("5. Exit");
            System.out.print("Choice: ");
            
            int choice = scanner.nextInt();
            scanner.nextLine();
            
            switch (choice) {
                case 1:
                    System.out.print("Host: ");
                    String host = scanner.nextLine();
                    System.out.print("Port: ");
                    int port = scanner.nextInt();
                    peer.connectToPeer(host, port);
                    break;
                    
                case 2:
                    System.out.print("Peer address (host:port): ");
                    String peerAddr = scanner.nextLine();
                    System.out.print("Message: ");
                    String msg = scanner.nextLine();
                    peer.sendMessageToPeer(peerAddr, msg);
                    break;
                    
                case 3:
                    System.out.print("Broadcast message: ");
                    String broadMsg = scanner.nextLine();
                    peer.broadcastMessage(broadMsg);
                    break;
                    
                case 4:
                    peer.listConnections();
                    break;
                    
                case 5:
                    System.exit(0);
                    break;
                    
                default:
                    System.out.println("Invalid choice");
            }
        }
    }
}


