package com.p2pclient.ui.fx;

import com.p2pclient.remote.ScreenQualityProfile;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

import java.util.function.Consumer;

public class SettingsController {
    @FXML
    private ComboBox<String> themeComboBox;
    @FXML
    private ComboBox<String> qualityComboBox;
    @FXML
    private CheckBox logRelayCheckBox;
    @FXML
    private CheckBox logUiEventsCheckBox;
    @FXML
    private Label qualityHelpLabel;

    private FxClientCoordinator coordinator;
    private Consumer<String> themeChangeListener;

    @FXML
    public void initialize() {
        themeComboBox.setItems(FXCollections.observableArrayList("light", "dark"));
        themeComboBox.getSelectionModel().select("light");
        themeComboBox.setOnAction(e -> {
            if (themeChangeListener != null) {
                themeChangeListener.accept(themeComboBox.getSelectionModel().getSelectedItem());
            }
        });

        qualityComboBox.setItems(FXCollections.observableArrayList(
            ScreenQualityProfile.WAN_SAFE.name(),
            ScreenQualityProfile.WAN_ULTRA.name(),
            ScreenQualityProfile.LAN_HIGH.name()
        ));
        qualityComboBox.getSelectionModel().select(ScreenQualityProfile.WAN_SAFE.name());
        qualityHelpLabel.setText("WAN_SAFE for internet links · WAN_ULTRA for slow links · LAN_HIGH for local networks.");
        qualityComboBox.setOnAction(e -> {
            if (coordinator != null) {
                String selected = qualityComboBox.getSelectionModel().getSelectedItem();
                coordinator.onQualityProfileChanged(selected);
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

    public void setQualityProfile(ScreenQualityProfile profile) {
        Platform.runLater(() -> qualityComboBox.getSelectionModel().select(profile.name()));
    }

    public boolean verboseRelay() {
        return logRelayCheckBox.isSelected();
    }

    public boolean verboseUi() {
        return logUiEventsCheckBox.isSelected();
    }
}
