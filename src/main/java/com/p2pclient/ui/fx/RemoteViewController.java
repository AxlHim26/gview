package com.p2pclient.ui.fx;

import com.p2pclient.model.P2PMessage;
import com.p2pclient.remote.InputForwarder;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelWriter;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteViewController {
    @FXML
    private Canvas remoteCanvas;
    @FXML
    private Label overlayLabel;
    @FXML
    private Label metricsLabel;
    @FXML
    private Label roleLabel;
    @FXML
    private StackPane remoteSurface;
    @FXML
    private Button fullScreenButton;
    @FXML
    private VBox chatContainer;
    @FXML
    private TextArea chatArea;
    @FXML
    private TextField chatInput;

    private FxClientCoordinator coordinator;
    private InputForwarder inputForwarder;
    private boolean controller;
    private int lastPressedButton = 0; // Track last pressed button for proper release
    private double smoothedLatency = -1;
    private double smoothedSenderCpu = -1;
    private double lastRenderFps = 0.0d;
    private int lastEstimatedBitrateKbps = 0;
    private boolean lowResourceMode;
    private long lastMetricsUpdate = 0L;
    private WritableImageHolder currentImage = new WritableImageHolder();
    private final AtomicLong renderWindowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong renderFrames = new AtomicLong();
    private Pane remoteSurfaceParent;
    private int remoteSurfaceIndex = -1;
    private boolean fullScreenActive = false;

    @FXML
    public void initialize() {
        remoteCanvas.setFocusTraversable(true);
        // Set initial size to avoid 0x0 canvas
        remoteCanvas.setWidth(820);
        remoteCanvas.setHeight(480);
        // Bind canvas size to remoteSurface size after scene is ready
        Platform.runLater(() -> {
            if (remoteSurface.getWidth() > 0 && remoteSurface.getHeight() > 0) {
                remoteCanvas.widthProperty().unbind();
                remoteCanvas.heightProperty().unbind();
                remoteCanvas.widthProperty().bind(remoteSurface.widthProperty());
                remoteCanvas.heightProperty().bind(remoteSurface.heightProperty());
            } else {
                // If parent not ready, listen for width changes
                remoteSurface.widthProperty().addListener((obs, oldVal, newVal) -> {
                    if (newVal.doubleValue() > 0 && !remoteCanvas.widthProperty().isBound()) {
                        remoteCanvas.widthProperty().unbind();
                        remoteCanvas.heightProperty().unbind();
                        remoteCanvas.widthProperty().bind(remoteSurface.widthProperty());
                        remoteCanvas.heightProperty().bind(remoteSurface.heightProperty());
                    }
                });
            }
        });
        // Also handle parent changes for fullscreen mode
        remoteCanvas.parentProperty().addListener((obs, oldParent, newParent) -> {
            if (newParent instanceof Region region) {
                remoteCanvas.widthProperty().unbind();
                remoteCanvas.heightProperty().unbind();
                remoteCanvas.widthProperty().bind(region.widthProperty());
                remoteCanvas.heightProperty().bind(region.heightProperty());
            }
        });
        remoteCanvas.setOnMousePressed(this::handleMousePressed);
        remoteCanvas.setOnMouseReleased(this::handleMouseReleased);
        remoteCanvas.setOnMouseDragged(this::handleMouseDragged);
        remoteCanvas.setOnMouseMoved(this::handleMouseMoved);
        remoteCanvas.setOnKeyPressed(this::handleKeyPressed);
        remoteCanvas.setOnKeyReleased(this::handleKeyReleased);
        drawPlaceholder();
    }

    public void setCoordinator(FxClientCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setInputForwarder(InputForwarder forwarder) {
        this.inputForwarder = forwarder;
    }

    public void setController(boolean controller) {
        this.controller = controller;
        Platform.runLater(() -> roleLabel.setText(controller ? "Role: Controller" : "Role: Controlled"));
    }

    @FXML
    private void toggleFullScreen() {
        if (coordinator == null) {
            return;
        }
        boolean full = coordinator.toggleFullScreen();
        setFullScreenState(full);
    }

    @FXML
    private void handleDisconnect() {
        if (coordinator != null) {
            coordinator.disconnect();
        }
    }

    @FXML
    private void sendChat() {
        String text = chatInput.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        appendChat("Me: " + text.trim());
        chatInput.clear();
    }

    public void appendChat(String message) {
        Platform.runLater(() -> {
            if (chatArea.getText().isBlank()) {
                chatArea.setText(message);
            } else {
                chatArea.appendText("\n" + message);
            }
            chatArea.setScrollTop(Double.MAX_VALUE);
        });
    }


    public void setFullScreenState(boolean full) {
        Platform.runLater(() -> {
            fullScreenActive = full;
            if (fullScreenButton != null) {
                fullScreenButton.setText(full ? "Exit Fullscreen" : "Fullscreen");
            }
        });
    }

    public Node detachRemoteSurface() {
        if (remoteSurface == null) {
            return null;
        }
        Parent parent = remoteSurface.getParent();
        if (!(parent instanceof Pane pane)) {
            return remoteSurface;
        }
        if (remoteSurfaceParent == null) {
            remoteSurfaceParent = pane;
            remoteSurfaceIndex = pane.getChildren().indexOf(remoteSurface);
        }
        pane.getChildren().remove(remoteSurface);
        return remoteSurface;
    }

    public void restoreRemoteSurface() {
        if (remoteSurfaceParent == null || remoteSurface == null) {
            return;
        }
        var children = remoteSurfaceParent.getChildren();
        if (!children.contains(remoteSurface)) {
            if (remoteSurfaceIndex >= 0 && remoteSurfaceIndex <= children.size()) {
                children.add(remoteSurfaceIndex, remoteSurface);
            } else {
                children.add(remoteSurface);
            }
        }
        // Ensure chat stays visible when coming back from fullscreen
        if (chatContainer != null) {
            chatContainer.setVisible(true);
            chatContainer.setManaged(true);
        }
        // Reset sizing hints so HBox can re-measure correctly
        remoteSurface.setMinSize(0, 0);
        remoteSurface.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        remoteSurface.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        // Rebind canvas to remoteSurface after restore
        Platform.runLater(() -> {
            if (remoteSurface.getWidth() > 0 && remoteSurface.getHeight() > 0) {
                remoteCanvas.widthProperty().unbind();
                remoteCanvas.heightProperty().unbind();
                remoteCanvas.widthProperty().bind(remoteSurface.widthProperty());
                remoteCanvas.heightProperty().bind(remoteSurface.heightProperty());
            }
            remoteSurface.requestLayout();
            if (remoteSurface.getParent() != null) {
                remoteSurface.getParent().requestLayout();
            }
        });
        remoteSurfaceParent = null;
        remoteSurfaceIndex = -1;
    }

    public void relayoutAfterFullscreenExit() {
        Platform.runLater(() -> {
            // Unbind canvas completely first to reset any fullscreen bindings
            remoteCanvas.widthProperty().unbind();
            remoteCanvas.heightProperty().unbind();
            
            if (chatContainer != null) {
                chatContainer.setVisible(true);
                chatContainer.setManaged(true);
                // Ensure chat container maintains its fixed width
                chatContainer.setPrefWidth(360);
                chatContainer.setMinWidth(360);
                chatContainer.setMaxWidth(360);
                chatContainer.applyCss();
                chatContainer.layout();
            }
            if (remoteSurface != null) {
                // Reset remoteSurface size constraints to allow proper fill
                remoteSurface.setMinSize(0, 0);
                remoteSurface.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
                remoteSurface.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                
                // Ensure parent HBox fills properly
                Parent parent = remoteSurface.getParent();
                if (parent instanceof Region parentRegion) {
                    parentRegion.setMinSize(0, 0);
                    parentRegion.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
                    parentRegion.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                }
                
                remoteSurface.applyCss();
                remoteSurface.layout();
                
                if (parent != null) {
                    parent.applyCss();
                    parent.requestLayout();
                }
                if (remoteSurface.getScene() != null && remoteSurface.getScene().getRoot() != null) {
                    remoteSurface.getScene().getRoot().applyCss();
                    remoteSurface.getScene().getRoot().requestLayout();
                }
                
                // Rebind canvas after layout is complete
                Platform.runLater(() -> {
                    if (remoteSurface.getWidth() > 0 && remoteSurface.getHeight() > 0) {
                        remoteCanvas.widthProperty().bind(remoteSurface.widthProperty());
                        remoteCanvas.heightProperty().bind(remoteSurface.heightProperty());
                    } else {
                        // If size not ready, wait for next layout pass
                        remoteSurface.widthProperty().addListener((obs, oldVal, newVal) -> {
                            if (newVal.doubleValue() > 0 && remoteSurface.getHeight() > 0) {
                                remoteCanvas.widthProperty().unbind();
                                remoteCanvas.heightProperty().unbind();
                                remoteCanvas.widthProperty().bind(remoteSurface.widthProperty());
                                remoteCanvas.heightProperty().bind(remoteSurface.heightProperty());
                            }
                        });
                    }
                });
            }
        });
    }

    public void updateRemoteScreen(BufferedImage image, P2PMessage metadata) {
        if (image == null) {
            clearScreen();
            return;
        }
        currentImage.update(image);
        if (inputForwarder != null) {
            inputForwarder.updateRemoteImageSize(image.getWidth(), image.getHeight());
        }
        renderFrames.incrementAndGet();
        long now = System.currentTimeMillis();
        long windowStart = renderWindowStart.get();
        if (now - windowStart > 2000) {
            long frames = renderFrames.getAndSet(0);
            long duration = now - windowStart;
            if (duration > 0) {
                lastRenderFps = frames * 1000.0 / duration;
            }
            renderWindowStart.set(now);
        }
        updateMetrics(metadata);
        Platform.runLater(this::drawFrame);
    }

    public void clearScreen() {
        currentImage.clear();
        smoothedLatency = -1;
        smoothedSenderCpu = -1;
        lastRenderFps = 0;
        lastEstimatedBitrateKbps = 0;
        lowResourceMode = false;
        Platform.runLater(this::drawPlaceholder);
    }

    private void drawFrame() {
        GraphicsContext gc = remoteCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#0b1a29"));
        gc.fillRect(0, 0, remoteCanvas.getWidth(), remoteCanvas.getHeight());

        WritableImageHolder.ImageSnapshot snapshot = currentImage.snapshot();
        if (snapshot == null) {
            overlayLabel.setText("Waiting for frames");
            overlayLabel.setVisible(true);
            return;
        }
        
        double canvasW = remoteCanvas.getWidth();
        double canvasH = remoteCanvas.getHeight();
        double imageW = snapshot.width();
        double imageH = snapshot.height();
        
        if (imageW <= 0 || imageH <= 0 || canvasW <= 0 || canvasH <= 0) {
            overlayLabel.setText("Waiting for frames");
            overlayLabel.setVisible(true);
            return;
        }
        
        // Keep aspect ratio; allow upscale only when fullscreen is active
        double scaleX = canvasW / imageW;
        double scaleY = canvasH / imageH;
        double scale = Math.min(scaleX, scaleY);
        if (!fullScreenActive) {
            scale = Math.min(scale, 1.0);
        }

        double scaledW = imageW * scale;
        double scaledH = imageH * scale;
        double x = (canvasW - scaledW) / 2.0;
        double y = (canvasH - scaledH) / 2.0;

        gc.drawImage(snapshot.image(), x, y, scaledW, scaledH);
        overlayLabel.setVisible(false);
    }

    private void drawPlaceholder() {
        GraphicsContext gc = remoteCanvas.getGraphicsContext2D();
        gc.setFill(Color.web("#0b1a29"));
        gc.fillRect(0, 0, remoteCanvas.getWidth(), remoteCanvas.getHeight());
        gc.setFill(Color.web("#d3dce6"));
        gc.setFont(Font.font(16));
        gc.fillText("No session active", 20, 30);
        overlayLabel.setText("Waiting for session");
        overlayLabel.setVisible(true);
    }

    private void handleMousePressed(MouseEvent event) {
        remoteCanvas.requestFocus();
        sendMouse(event, true);
    }

    private void handleMouseReleased(MouseEvent event) {
        sendMouse(event, false);
    }

    private void handleMouseDragged(MouseEvent event) {
        sendMouseMove(event);
    }

    private void handleMouseMoved(MouseEvent event) {
        sendMouseMove(event);
    }

    private void handleKeyPressed(KeyEvent event) {
        if (!controller || coordinator == null || inputForwarder == null) {
            return;
        }
        int modifiers = buildModifierMask(event);
        P2PMessage message = inputForwarder.createKeyboardMessage(event.getCode().getCode(), true, modifiers);
        coordinator.handleKeyboardEvent(message);
    }

    private void handleKeyReleased(KeyEvent event) {
        if (!controller || coordinator == null || inputForwarder == null) {
            return;
        }
        int modifiers = buildModifierMask(event);
        P2PMessage message = inputForwarder.createKeyboardMessage(event.getCode().getCode(), false, modifiers);
        coordinator.handleKeyboardEvent(message);
    }

    private void sendMouse(MouseEvent event, boolean pressed) {
        if (!controller || coordinator == null || inputForwarder == null) {
            return;
        }
        Point2D coords = toImageCoordinates(event.getX(), event.getY());
        
        // When pressing, get button from event and save it
        // When releasing, use saved button (event.getButton() may be NONE/null on release)
        int button;
        if (pressed) {
            button = switch (event.getButton()) {
                case PRIMARY -> 1;
                case SECONDARY -> 3;
                case MIDDLE -> 2;
                default -> 1;
            };
            lastPressedButton = button;
        } else {
            // Use last pressed button for release to ensure press/release match
            button = lastPressedButton;
        }
        
        int x = coords == null ? (int) event.getX() : (int) Math.round(coords.getX());
        int y = coords == null ? (int) event.getY() : (int) Math.round(coords.getY());
        P2PMessage message = inputForwarder.createMouseClickMessage(x, y, button, pressed);
        coordinator.handleMouseEvent(message);
    }

    private void sendMouseMove(MouseEvent event) {
        if (!controller || coordinator == null || inputForwarder == null) {
            return;
        }
        Point2D coords = toImageCoordinates(event.getX(), event.getY());
        int x = coords == null ? (int) event.getX() : (int) Math.round(coords.getX());
        int y = coords == null ? (int) event.getY() : (int) Math.round(coords.getY());
        P2PMessage message = inputForwarder.createMouseMoveMessage(x, y);
        coordinator.handleMouseEvent(message);
    }

    private Point2D toImageCoordinates(double x, double y) {
        WritableImageHolder.ImageSnapshot snapshot = currentImage.snapshot();
        if (snapshot == null || snapshot.width() <= 0 || snapshot.height() <= 0) {
            return null;
        }
        double canvasW = remoteCanvas.getWidth();
        double canvasH = remoteCanvas.getHeight();
        double imageW = snapshot.width();
        double imageH = snapshot.height();
        
        if (canvasW <= 0 || canvasH <= 0) {
            return null;
        }
        
        // Match the same aspect-fit logic as drawFrame
        double scaleX = canvasW / imageW;
        double scaleY = canvasH / imageH;
        double scale = Math.min(scaleX, scaleY);
        if (!fullScreenActive) {
            scale = Math.min(scale, 1.0);
        }

        double scaledW = imageW * scale;
        double scaledH = imageH * scale;
        double offsetX = (canvasW - scaledW) / 2.0;
        double offsetY = (canvasH - scaledH) / 2.0;

        // If click is outside the drawn image, ignore
        if (x < offsetX || x > offsetX + scaledW || y < offsetY || y > offsetY + scaledH) {
            return null;
        }

        // Convert canvas coordinates to image coordinates
        double imgX = (x - offsetX) / scale;
        double imgY = (y - offsetY) / scale;
        
        // Clamp to image bounds
        imgX = Math.max(0, Math.min(imageW - 1, imgX));
        imgY = Math.max(0, Math.min(imageH - 1, imgY));
        
        if (Double.isNaN(imgX) || Double.isNaN(imgY)) {
            return null;
        }
        return new Point2D(imgX, imgY);
    }

    private int buildModifierMask(KeyEvent event) {
        int mask = 0;
        if (event.isShiftDown()) {
            mask |= java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        }
        if (event.isControlDown()) {
            mask |= java.awt.event.InputEvent.CTRL_DOWN_MASK;
        }
        if (event.isAltDown()) {
            mask |= java.awt.event.InputEvent.ALT_DOWN_MASK;
        }
        if (event.isMetaDown()) {
            mask |= java.awt.event.InputEvent.META_DOWN_MASK;
        }
        return mask;
    }

    private void updateMetrics(P2PMessage metadata) {
        long now = System.currentTimeMillis();
        if (metadata != null && metadata.getTimestamp() > 0) {
            long latency = now - metadata.getTimestamp();
            if (latency >= 0) {
                smoothedLatency = smoothedLatency < 0 ? latency : (smoothedLatency * 0.7) + (latency * 0.3);
            }
            if (metadata.getSenderCpuLoad() >= 0) {
                double cpu = metadata.getSenderCpuLoad() * 100.0;
                smoothedSenderCpu = smoothedSenderCpu < 0 ? cpu : (smoothedSenderCpu * 0.6) + (cpu * 0.4);
            }
            if (metadata.getEstimatedBitrateKbps() > 0) {
                lastEstimatedBitrateKbps = metadata.getEstimatedBitrateKbps();
            }
            lowResourceMode = metadata.isLowResourceMode();
        }
        if (now - lastMetricsUpdate < 800) {
            return;
        }
        lastMetricsUpdate = now;
        String latencyText = smoothedLatency < 0 ? "-" : String.format("%.0f ms", smoothedLatency);
        String cpuText = smoothedSenderCpu < 0 ? "-" : String.format("%.0f%%", smoothedSenderCpu);
        String bitrateText = lastEstimatedBitrateKbps <= 0 ? "-" : lastEstimatedBitrateKbps + " kbps";
        String modeText = lowResourceMode ? "LOW" : "P2P";
        String fpsText = String.format("%.1f", lastRenderFps);
        Platform.runLater(() -> metricsLabel.setText(
            "fps: " + fpsText + " | rtt: " + latencyText + " | cpu: " + cpuText + " | br: " + bitrateText + " | mode: " + modeText));
    }

    private static final class WritableImageHolder {
        private WritableImage image;
        private int width;
        private int height;

        void update(BufferedImage bufferedImage) {
            int w = bufferedImage.getWidth();
            int h = bufferedImage.getHeight();
            this.width = w;
            this.height = h;
            this.image = new WritableImage(w, h);
            PixelWriter writer = image.getPixelWriter();
            int[] pixels = bufferedImage.getRGB(0, 0, w, h, null, 0, w);
            writer.setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        }

        void clear() {
            this.image = null;
            this.width = 0;
            this.height = 0;
        }

        ImageSnapshot snapshot() {
            if (image == null) {
                return null;
            }
            return new ImageSnapshot(image, width, height);
        }

        record ImageSnapshot(javafx.scene.image.WritableImage image, int width, int height) {}
    }
}
