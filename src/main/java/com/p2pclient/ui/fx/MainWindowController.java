package com.p2pclient.ui.fx;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class MainWindowController {
    @FXML
    private StackPane contentStack;
    @FXML
    private VBox registrationPane;
    @FXML
    private BorderPane controlPane;
    @FXML
    private VBox sessionsContainer;
    @FXML
    private StackPane remoteContainer;
    @FXML
    private Label statusLabel;
    @FXML
    private Label connectionModeLabel;
    @FXML
    private Label incomingInfoLabel;
    @FXML
    private TextArea regChatArea;
    @FXML
    private TextField regChatInput;
    @FXML
    private Label regAudioStatusLabel;
    @FXML
    private javafx.scene.control.Button regAudioButton;
    @FXML
    private javafx.scene.control.Button regMuteButton;

    private SessionsController sessionsController;
    private RemoteViewController remoteViewController;
    private FxClientCoordinator coordinator;
    private Stage stage;
    private Scene scene;
    private Scene remoteOnlyScene;
    private Scene originalScene;
    private boolean darkTheme;
    private boolean remoteFullScreen;
    private double savedWindowWidth = 1400;
    private double savedWindowHeight = 900;
    private double savedWindowX = Double.NaN;
    private double savedWindowY = Double.NaN;
    private boolean savedWindowMaximized = false;

    @FXML
    public void initialize() {
        try {
            loadSessions();
            loadRemoteView();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load UI fragments", e);
        }

        coordinator = new FxClientCoordinator(this, sessionsController, remoteViewController, null);
        sessionsController.setCoordinator(coordinator);
        remoteViewController.setCoordinator(coordinator);
        coordinator.bootstrap();
        showRegistrationView();
    }

    public void onStageReady(Stage stage, Scene scene) {
        this.stage = stage;
        this.scene = scene;
        this.originalScene = scene;
        coordinator.attachStage(stage);
        stage.fullScreenProperty().addListener((obs, wasFull, isFull) -> {
            if (!isFull && remoteFullScreen) {
                exitRemoteOnlyFullscreen();
            }
        });
    }

    private void loadSessions() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/Sessions.fxml"));
        Parent node = loader.load();
        sessionsController = loader.getController();
        sessionsContainer.getChildren().add(node);
    }

    private void loadRemoteView() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RemoteView.fxml"));
        Parent node = loader.load();
        remoteViewController = loader.getController();
        remoteContainer.getChildren().add(node);
    }

    @FXML
    public void toggleTheme() {
        applyTheme(darkTheme ? "light" : "dark");
    }

    public void applyTheme(String theme) {
        darkTheme = Objects.equals("dark", theme);
        if (scene == null) {
            return;
        }
        String stylesheet = darkTheme ? "/styles/dark.css" : "/styles/light.css";
        if (scene != null) {
            scene.getStylesheets().clear();
            scene.getStylesheets().add(getClass().getResource(stylesheet).toExternalForm());
        }
        if (remoteOnlyScene != null) {
            remoteOnlyScene.getStylesheets().clear();
            remoteOnlyScene.getStylesheets().add(getClass().getResource(stylesheet).toExternalForm());
        }
    }

    @FXML
    public void exitApp() {
        Platform.exit();
    }

    @FXML
    public void sendRegistrationChat() {
        String text = regChatInput.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.trim();
        appendRegistrationChat("Me: " + trimmed);
        if (coordinator != null) {
            coordinator.sendChatMessage(trimmed);
        }
        regChatInput.clear();
    }

    @FXML
    public void toggleAudioSendReg() {
        if (coordinator == null) {
            return;
        }
        if (coordinator.isAudioSending()) {
            coordinator.stopAudioSending();
        } else {
            coordinator.startAudioSending();
        }
        updateAudioUi(coordinator.isAudioSending(), coordinator.isPlaybackMuted());
    }

    @FXML
    public void toggleMuteOutputReg() {
        if (coordinator == null) {
            return;
        }
        boolean mute = !coordinator.isPlaybackMuted();
        coordinator.setPlaybackMuted(mute);
        updateAudioUi(coordinator.isAudioSending(), mute);
    }

    public void updateAudioUi(boolean sending, boolean muted) {
        Platform.runLater(() -> {
            if (regAudioButton != null) {
                regAudioButton.setText(sending ? "Tắt mic" : "Bật mic");
            }
            if (regMuteButton != null) {
                regMuteButton.setText(muted ? "Bật loa" : "Tắt loa");
            }
            if (regAudioStatusLabel != null) {
                String status = sending ? "Audio: đang gửi" : "Audio: tắt";
                if (muted) {
                    status += " | loa tắt";
                }
                regAudioStatusLabel.setText(status);
            }
        });
    }

    private void appendRegistrationChat(String message) {
        Platform.runLater(() -> {
            if (regChatArea.getText().isBlank()) {
                regChatArea.setText(message);
            } else {
                regChatArea.appendText("\n" + message);
            }
            regChatArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    public void appendIncomingChat(String message) {
        appendRegistrationChat(message);
    }

    @FXML
    public void handleDisconnect() {
        if (coordinator != null) {
            coordinator.disconnect();
        }
    }

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void updateConnectionMode(String mode) {
        Platform.runLater(() -> connectionModeLabel.setText("Mode: " + mode));
    }

    public void showControlView() {
        Platform.runLater(() -> {
            registrationPane.setVisible(false);
            registrationPane.setManaged(false);
            controlPane.setVisible(true);
            controlPane.setManaged(true);
            // Resize window to larger size for control view
            if (stage != null) {
                stage.setMinWidth(1400);
                stage.setMinHeight(780);
                stage.setWidth(1400);
                stage.setHeight(780);
                stage.setMaxWidth(Double.MAX_VALUE);
                stage.setMaxHeight(Double.MAX_VALUE);
            }
        });
    }

    public void showRegistrationView() {
        Platform.runLater(() -> {
            controlPane.setVisible(false);
            controlPane.setManaged(false);
            registrationPane.setVisible(true);
            registrationPane.setManaged(true);
            // Resize window back to smaller size for registration view
            if (stage != null) {
                stage.setMinWidth(1040);
                stage.setMinHeight(740);
                stage.setWidth(1040);
                stage.setHeight(740);
                stage.setMaxWidth(1040);
                stage.setMaxHeight(740);
            }
        });
    }

    public void setIncomingControllerInfo(String peerId, String ip, Integer port) {
        Platform.runLater(() -> {
            if (peerId == null) {
                incomingInfoLabel.setText("Chưa có bên nào điều khiển bạn");
            } else {
                String endpoint = ip != null && port != null ? ip + ":" + port : "đang xác định";
                incomingInfoLabel.setText("Đang được điều khiển bởi " + peerId + " (" + endpoint + ")");
            }
        });
    }

    public boolean toggleRemoteOnlyFullscreen() {
        if (stage == null || remoteViewController == null) {
            return false;
        }
        if (remoteFullScreen) {
            stage.setFullScreen(false);
            return false;
        }
        enterRemoteOnlyFullscreen();
        return true;
    }

    private void enterRemoteOnlyFullscreen() {
        if (remoteFullScreen || remoteViewController == null || stage == null) {
            return;
        }
        // Remember current window bounds so we can restore them after exiting fullscreen
        savedWindowWidth = stage.getWidth();
        savedWindowHeight = stage.getHeight();
        savedWindowX = stage.getX();
        savedWindowY = stage.getY();
        savedWindowMaximized = stage.isMaximized();
        Node surface = remoteViewController.detachRemoteSurface();
        if (surface == null) {
            return;
        }
        BorderPane fsRoot = new BorderPane();
        fsRoot.setCenter(surface);
        remoteOnlyScene = new Scene(fsRoot);
        if (scene != null) {
            remoteOnlyScene.getStylesheets().addAll(scene.getStylesheets());
        }
        remoteFullScreen = true;
        remoteViewController.setFullScreenState(true);
        stage.setScene(remoteOnlyScene);
        stage.setFullScreen(true);
    }

    private void exitRemoteOnlyFullscreen() {
        if (!remoteFullScreen || remoteViewController == null) {
            return;
        }
        remoteFullScreen = false;
        Platform.runLater(() -> {
            // First, ensure fullscreen is off
            if (stage.isFullScreen()) {
                stage.setFullScreen(false);
            }
            // Restore scene first
            stage.setScene(originalScene);
            // IMPORTANT: Hide registration view and show control view
            registrationPane.setVisible(false);
            registrationPane.setManaged(false);
            controlPane.setVisible(true);
            controlPane.setManaged(true);
            // Restore remote surface AFTER control pane is visible
            remoteViewController.restoreRemoteSurface();
            // Restore window bounds after leaving fullscreen
            stage.setMaximized(false);
            stage.setIconified(false);
            // Set size constraints and actual size
            stage.setMinWidth(1400);
            stage.setMinHeight(780);
            stage.setMaxWidth(Double.MAX_VALUE);
            stage.setMaxHeight(Double.MAX_VALUE);
            // Always restore to control view size (1400x740) to ensure correct layout
            // This ensures Chat: 360px width, Remote Surface: 1006px width
            stage.setWidth(1400);
            stage.setHeight(780);
            // Restore window position if available
            if (!Double.isNaN(savedWindowX) && !Double.isNaN(savedWindowY)) {
                stage.setX(savedWindowX);
                stage.setY(savedWindowY);
            } else {
                stage.centerOnScreen();
            }
            // Request layout to refresh - do this after a small delay to ensure everything is ready
            Platform.runLater(() -> {
                if (originalScene.getRoot() != null) {
                    originalScene.getRoot().applyCss();
                    originalScene.getRoot().layout();
                }
                if (controlPane != null) {
                    controlPane.applyCss();
                    controlPane.layout();
                }
                if (remoteContainer != null) {
                    remoteContainer.applyCss();
                    remoteContainer.layout();
                }
                remoteViewController.relayoutAfterFullscreenExit();
            });
            remoteOnlyScene = null;
            remoteViewController.setFullScreenState(false);
        });
    }

    public Stage getStage() {
        return stage;
    }
}
