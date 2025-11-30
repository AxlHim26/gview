package com.p2pclient.ui.fx;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
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
    private Label statusLabel;
    @FXML
    private Label connectionModeLabel;

    private SessionsController sessionsController;
    private RemoteViewController remoteViewController;
    private FxClientCoordinator coordinator;
    private Stage stage;
    private Scene scene;
    private Scene remoteOnlyScene;
    private Scene originalScene;
    private boolean darkTheme;
    private boolean remoteFullScreen;

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

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void updateConnectionMode(String mode) {
        Platform.runLater(() -> connectionModeLabel.setText("Mode: " + mode));
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
        stage.setFullScreen(true);
        return true;
    }

    private void enterRemoteOnlyFullscreen() {
        if (remoteFullScreen || remoteViewController == null) {
            return;
        }
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
    }

    private void exitRemoteOnlyFullscreen() {
        if (!remoteFullScreen || remoteViewController == null) {
            return;
        }
        remoteViewController.restoreRemoteSurface();
        stage.setScene(originalScene);
        stage.setMaximized(true);
        remoteFullScreen = false;
        remoteOnlyScene = null;
        remoteViewController.setFullScreenState(false);
    }

    public Stage getStage() {
        return stage;
    }
}
