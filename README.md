# P2P Remote Desktop Client

Ứng dụng Java Swing Desktop Client cho hệ thống remote desktop peer-to-peer, kết nối với ID Server để quản lý peer discovery và routing.

## Tính năng

- **Đăng ký Peer**: Tạo peer ID duy nhất từ ID Server
- **Kết nối WebSocket**: Duy trì kết nối real-time với ID Server
- **Tìm kiếm Peer**: Tìm và kết nối với các peer khác
- **Kết nối P2P**: Kết nối trực tiếp giữa các peer qua Socket
- **Remote Desktop**: Chia sẻ màn hình và điều khiển từ xa
- **GUI Swing**: Giao diện người dùng thân thiện

## Yêu cầu

- Java 17 hoặc cao hơn
- Maven 3.6+
- ID Server đang chạy tại `http://localhost:8080`
- PostgreSQL database (cho ID Server)

## Cài đặt

1. Đảm bảo ID Server đang chạy:
   ```bash
   cd IDServer
   mvn spring-boot:run
   ```

2. Build project:
   ```bash
   cd chat.app
   mvn clean install
   ```

3. Chạy ứng dụng:
   ```bash
   mvn exec:java -Dexec.mainClass="com.p2pclient.P2PClientApp"
   ```
   
   Hoặc chạy JAR file:
   ```bash
   java -jar target/p2p-remote-desktop-client-1.0.0.jar
   ```

## Sử dụng

### 1. Đăng ký Peer mới

1. Khởi động ứng dụng
2. Nhập password
3. Click "Register New Peer"
4. Ứng dụng sẽ:
   - Đăng ký với ID Server
   - Nhận peer ID (format: XXX-XXX-XXX)
   - Khởi động P2P server
   - Kết nối WebSocket với ID Server
   - Chuyển sang Dashboard

### 2. Kết nối với Peer ID có sẵn

1. Nhập peer ID và password
2. Click "Connect with Existing ID"
3. Ứng dụng sẽ kết nối và hiển thị Dashboard

### 3. Kết nối đến Peer khác

1. Tại Dashboard, nhập:
   - Target Peer ID
   - Password
2. Click "Connect to Peer"
3. Ứng dụng sẽ:
   - Tìm kiếm peer trên ID Server
   - Kết nối P2P trực tiếp
   - Hiển thị màn hình remote (nếu bạn là controller)
   - Cho phép điều khiển remote (nếu bạn là controller)

### 4. Remote Desktop

- **Controller**: Peer khởi tạo kết nối sẽ nhận màn hình từ peer được điều khiển
- **Controlled**: Peer được kết nối sẽ gửi màn hình và nhận lệnh điều khiển

## Cấu trúc Project

```
src/main/java/com/p2pclient/
├── P2PClientApp.java          # Main class
├── gui/
│   ├── MainFrame.java         # Main window
│   ├── LoginPanel.java        # Login/Register panel
│   ├── DashboardPanel.java    # Dashboard
│   └── RemoteControlPanel.java # Remote screen display
├── network/
│   ├── IdServerClient.java    # REST API + WebSocket client
│   ├── P2PServer.java         # P2P server (accept connections)
│   └── P2PClient.java          # P2P client (connect to peers)
├── remote/
│   ├── ScreenCapture.java     # Screen capture
│   ├── ScreenReceiver.java    # Receive and display screen
│   └── InputForwarder.java    # Forward mouse/keyboard events
├── model/
│   ├── PeerInfo.java          # Peer information
│   ├── ConnectionStatus.java  # Connection status enum
│   └── P2PMessage.java        # P2P message format
└── util/
    └── NetworkUtils.java      # Network utilities
```

## Cấu hình

File `src/main/resources/config.properties`:

```properties
idserver.url=http://localhost:8080
idserver.ws.url=ws://localhost:8080/ws
p2p.port.range.start=50000
p2p.port.range.end=60000
screen.capture.fps=10
heartbeat.interval.seconds=30
```

## Giao thức P2P

Ứng dụng sử dụng Java Object Serialization để truyền tin nhắn P2P:

- **SCREEN**: Gửi màn hình (BufferedImage dưới dạng JPEG bytes)
- **MOUSE**: Sự kiện chuột (move, click)
- **KEYBOARD**: Sự kiện bàn phím
- **DISCONNECT**: Ngắt kết nối

## Xử lý lỗi

- Hiển thị dialog lỗi cho các lỗi network
- Logging với SLF4J
- Tự động ngắt kết nối khi có lỗi
- Reconnection logic cho WebSocket

## Keyboard Shortcuts

- `Ctrl+Q` hoặc `Cmd+Q`: Thoát ứng dụng

## Lưu ý

- Ứng dụng cần quyền truy cập màn hình và input devices
- Firewall có thể chặn kết nối P2P
- Đảm bảo port range 50000-60000 không bị chặn
- Screen capture có thể ảnh hưởng đến hiệu suất

## Troubleshooting

1. **Không kết nối được đến ID Server**:
   - Kiểm tra ID Server đang chạy
   - Kiểm tra URL trong config.properties

2. **Không kết nối được P2P**:
   - Kiểm tra firewall
   - Đảm bảo cả hai peer đều online
   - Kiểm tra port range

3. **Màn hình không hiển thị**:
   - Kiểm tra quyền truy cập màn hình
   - Kiểm tra log để xem có lỗi không

## Phát triển

### Build và chạy tests:
```bash
mvn clean test
```

### Tạo JAR với dependencies:
```bash
mvn clean package
```

## License

MIT License

