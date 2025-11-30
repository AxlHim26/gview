package com.p2pclient.ui.fx;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MainFxApplication extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/styles/light.css").toExternalForm());

        stage.setTitle("P2P Remote Desktop (JavaFX)");
        var iconStream = getClass().getResourceAsStream("/icons/app-icon.png");
        if (iconStream != null) {
            stage.getIcons().add(new Image(iconStream));
        }
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();

        MainWindowController controller = loader.getController();
        controller.onStageReady(stage, scene);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
