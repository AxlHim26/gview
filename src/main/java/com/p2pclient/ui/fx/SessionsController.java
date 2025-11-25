package com.p2pclient.ui.fx;

import com.p2pclient.remote.ScreenQualityProfile;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.paint.Color;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class SessionsController {
    @FXML
    private PasswordField passwordField;
    @FXML
    private TextField existingPeerIdField;
    @FXML
    private TextField targetPeerIdField;
    @FXML
    private PasswordField connectPasswordField;
    @FXML
    private ChoiceBox<String> qualityChoiceBox;
    @FXML
    private Button connectButton;
    @FXML
    private Button disconnectButton;
    @FXML
    private Button registerButton;
    @FXML
    private Button useExistingButton;
    @FXML
    private Label myPeerIdLabel;
    @FXML
    private Label identityStatusLabel;
    @FXML
    private Label connectionModeLabel;
    @FXML
    private TableView<SessionRow> sessionTable;
    @FXML
    private TableColumn<SessionRow, String> peerColumn;
    @FXML
    private TableColumn<SessionRow, String> modeColumn;
    @FXML
    private TableColumn<SessionRow, String> statusColumn;
    @FXML
    private TextArea logArea;

    private FxClientCoordinator coordinator;
    private final ObservableList<SessionRow> sessions = FXCollections.observableArrayList();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML
    public void initialize() {
        peerColumn.setCellValueFactory(data -> data.getValue().peerProperty());
        modeColumn.setCellValueFactory(data -> data.getValue().modeProperty());
        statusColumn.setCellValueFactory(data -> data.getValue().statusProperty());
        sessionTable.setItems(sessions);

        qualityChoiceBox.setItems(FXCollections.observableArrayList(
            "WAN_SAFE",
            "WAN_ULTRA",
            "LAN_HIGH"
        ));
        qualityChoiceBox.getSelectionModel().select("WAN_SAFE");
        qualityChoiceBox.setOnAction(e -> {
            if (coordinator != null) {
                String selected = qualityChoiceBox.getSelectionModel().getSelectedItem();
                coordinator.onQualityProfileChanged(selected);
            }
        });
    }

    public void setCoordinator(FxClientCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @FXML
    public void registerPeer() {
        if (coordinator == null) {
            return;
        }
        String password = passwordField.getText();
        if (password == null || password.isBlank()) {
            showInlineStatus("Enter a password to register", true);
            return;
        }
        setBusy(true);
        coordinator.registerNewPeer(password.trim());
    }

    @FXML
    public void connectToPeer() {
        if (coordinator == null) {
            return;
        }
        String targetPeer = targetPeerIdField.getText();
        String password = connectPasswordField.getText();
        if (targetPeer == null || targetPeer.isBlank()) {
            appendLog("Please enter a peer id");
            return;
        }
        if (password == null || password.isBlank()) {
            appendLog("Please enter a password");
            return;
        }
        setConnectEnabled(false);
        coordinator.connectToPeer(targetPeer.trim(), password.trim());
    }

    @FXML
    public void connectExistingId() {
        if (coordinator == null) {
            return;
        }
        String peerId = existingPeerIdField.getText();
        String password = passwordField.getText();
        if (peerId == null || peerId.isBlank()) {
            showInlineStatus("Enter your existing peer id", true);
            return;
        }
        if (password == null || password.isBlank()) {
            showInlineStatus("Enter password for the existing id", true);
            return;
        }
        setBusy(true);
        coordinator.connectExistingPeer(peerId.trim(), password.trim());
    }

    @FXML
    public void disconnectFromPeer() {
        if (coordinator == null) {
            return;
        }
        coordinator.disconnect();
    }

    public void setBusy(boolean busy) {
        Platform.runLater(() -> {
            registerButton.setDisable(busy);
            connectButton.setDisable(busy);
            if (useExistingButton != null) {
                useExistingButton.setDisable(busy);
            }
        });
    }

    public void setConnectEnabled(boolean enabled) {
        Platform.runLater(() -> connectButton.setDisable(!enabled));
    }

    public void setDisconnectEnabled(boolean enabled) {
        Platform.runLater(() -> disconnectButton.setDisable(!enabled));
    }

    public void setPeerId(String peerId) {
        Platform.runLater(() -> myPeerIdLabel.setText("Peer ID: " + peerId));
    }

    public void showInlineStatus(String message, boolean error) {
        Platform.runLater(() -> {
            identityStatusLabel.setText(message);
            identityStatusLabel.setTextFill(error ? Color.FIREBRICK : Color.web("#4f6f8f"));
        });
    }

    public void setConnectionMode(String mode) {
        Platform.runLater(() -> connectionModeLabel.setText("Mode: " + mode));
    }

    public void appendLog(String message) {
        Platform.runLater(() -> {
            String timestamp = LocalDateTime.now().format(formatter);
            logArea.appendText("[" + timestamp + "] " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    public void updateActiveSession(String peer, String mode, String status) {
        Platform.runLater(() -> {
            SessionRow row;
            if (sessions.isEmpty()) {
                row = new SessionRow(peer, mode, status);
                sessions.add(row);
            } else {
                row = sessions.get(0);
                row.setPeer(peer);
                row.setMode(mode);
                row.setStatus(status);
                sessionTable.refresh();
            }
        });
    }

    public void resetConnectForm() {
        Platform.runLater(() -> {
            targetPeerIdField.clear();
            connectPasswordField.clear();
        });
    }

    public ScreenQualityProfile getSelectedProfile() {
        String selected = qualityChoiceBox.getSelectionModel().getSelectedItem();
        return selected == null ? ScreenQualityProfile.defaultProfile() : ScreenQualityProfile.fromCliArg(selected);
    }

    public void setSelectedProfile(ScreenQualityProfile profile) {
        Platform.runLater(() -> qualityChoiceBox.getSelectionModel().select(profile.name()));
    }

    public CompletableFuture<Boolean> confirmIncomingConnection(String sourcePeerId, String ip, Integer port) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Incoming connection");
            alert.setHeaderText("Peer " + sourcePeerId + " wants to connect");
            alert.setContentText("IP: " + ip + ":" + port + "\nAccept connection?");
            alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            Optional<ButtonType> result = alert.showAndWait();
            future.complete(result.isPresent() && result.get() == ButtonType.YES);
        });
        return future;
    }

    static class SessionRow {
        private final StringProperty peer = new SimpleStringProperty();
        private final StringProperty mode = new SimpleStringProperty();
        private final StringProperty status = new SimpleStringProperty();

        SessionRow(String peer, String mode, String status) {
            this.peer.set(peer);
            this.mode.set(mode);
            this.status.set(status);
        }

        public StringProperty peerProperty() { return peer; }
        public StringProperty modeProperty() { return mode; }
        public StringProperty statusProperty() { return status; }

        public void setPeer(String value) { peer.set(value); }
        public void setMode(String value) { mode.set(value); }
        public void setStatus(String value) { status.set(value); }
    }
}
