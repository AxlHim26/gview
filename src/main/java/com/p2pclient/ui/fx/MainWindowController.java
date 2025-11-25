package com.p2pclient.ui.fx;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class MainWindowController {
    @FXML
    private VBox sessionsContainer;
    @FXML
    private StackPane remoteContainer;
    @FXML
    private VBox settingsContainer;
    @FXML
    private Label statusLabel;
    @FXML
    private Label connectionModeLabel;

    private SessionsController sessionsController;
    private RemoteViewController remoteViewController;
    private SettingsController settingsController;
    private FxClientCoordinator coordinator;
    private Stage stage;
    private Scene scene;
    private boolean darkTheme;

    @FXML
    public void initialize() {
        try {
            loadSessions();
            loadRemoteView();
            loadSettings();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load UI fragments", e);
        }

        coordinator = new FxClientCoordinator(this, sessionsController, remoteViewController, settingsController);
        sessionsController.setCoordinator(coordinator);
        remoteViewController.setCoordinator(coordinator);
        settingsController.setCoordinator(coordinator);
        settingsController.setThemeChangeListener(this::applyTheme);
        coordinator.bootstrap();
    }

    public void onStageReady(Stage stage, Scene scene) {
        this.stage = stage;
        this.scene = scene;
        coordinator.attachStage(stage);
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

    private void loadSettings() throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/Settings.fxml"));
        Parent node = loader.load();
        settingsController = loader.getController();
        settingsContainer.getChildren().add(node);
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
        scene.getStylesheets().clear();
        String stylesheet = darkTheme ? "/styles/dark.css" : "/styles/light.css";
        scene.getStylesheets().add(getClass().getResource(stylesheet).toExternalForm());
        if (settingsController != null) {
            settingsController.selectTheme(theme);
        }
    }

    @FXML
    public void exitApp() {
        Platform.exit();
    }

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void updateConnectionMode(String mode) {
        Platform.runLater(() -> connectionModeLabel.setText("Mode: " + mode));
    }

    public Stage getStage() {
        return stage;
    }
}
