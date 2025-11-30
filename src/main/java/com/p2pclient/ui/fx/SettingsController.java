package com.p2pclient.ui.fx;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;

import java.util.function.Consumer;

public class SettingsController {
    @FXML
    private ComboBox<String> themeComboBox;
    @FXML
    private CheckBox logRelayCheckBox;
    @FXML
    private CheckBox logUiEventsCheckBox;

    private FxClientCoordinator coordinator;
    private Consumer<String> themeChangeListener;

    @FXML
    public void initialize() {
        themeComboBox.getItems().setAll("light", "dark");
        themeComboBox.getSelectionModel().select("light");
        themeComboBox.setOnAction(e -> {
            if (themeChangeListener != null) {
                themeChangeListener.accept(themeComboBox.getSelectionModel().getSelectedItem());
            }
        });
    }

    public void setCoordinator(FxClientCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setThemeChangeListener(Consumer<String> themeChangeListener) {
        this.themeChangeListener = themeChangeListener;
    }

    public void selectTheme(String theme) {
        Platform.runLater(() -> themeComboBox.getSelectionModel().select(theme));
    }

    public boolean verboseRelay() {
        return logRelayCheckBox.isSelected();
    }

    public boolean verboseUi() {
        return logUiEventsCheckBox.isSelected();
    }
}
