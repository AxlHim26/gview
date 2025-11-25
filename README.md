# gvieww-peer (JavaFX)

JavaFX-based UI for the P2P remote desktop client. Networking, models, and remote/relay logic are unchanged from the Swing build; only the UI layer was rebuilt with FXML controllers and JavaFX styling. The legacy Swing UI is preserved under `com.p2pclient.ui.swing` and can be launched via `com.p2pclient.ui.swing.MainSwingBackup`.

## Build & Run
- Requirements: JDK 17+, Maven, local OpenJFX runtime (pulled via Maven coordinates).
- Run the JavaFX client:
  ```bash
  mvn clean javafx:run
  ```
- Quick smoke test runner: `./scripts/run_ui_smoke_test.sh` (builds, then starts `javafx:run`).
- To build artifacts without running the UI:
  ```bash
  mvn -DskipTests package
  ```

## UI layout
- `src/main/resources/fxml/MainWindow.fxml` – shell window with menu/status and containers.
- `Sessions.fxml` – identity, session list, and connect/disconnect controls.
- `RemoteView.fxml` – canvas-based remote screen with latency/bitrate metrics and input capture.
- `Settings.fxml` – theme toggle (light/dark) and screen-quality selection.
- Styling: `src/main/resources/styles/light.css`, `src/main/resources/styles/dark.css`.
- Icons: `src/main/resources/icons/app-icon.png` (stage icon) and `app-icon.svg` (vector source).

## Core logic reuse
- Networking, relay, and capture logic live in `com.p2pclient.network`, `com.p2pclient.remote`, `com.p2pclient.model`, `com.p2pclient.util` (copied intact from the Swing repo).
- JavaFX glue: `com.p2pclient.ui.fx.FxClientCoordinator` wires services to controllers, replacing Swing EDT calls with `Platform.runLater` and `javafx.concurrent`-safe updates.
- Swing backup UI: `com.p2pclient.ui.swing.*` with the original `P2PClientApp` and panels; launch via `MainSwingBackup` if you need the legacy interface.

## Threading notes
- UI updates are marshalled onto the JavaFX Application Thread using `Platform.runLater`.
- Relay frame handling and screen capture run on background executors; UI receives decoded frames via `ScreenReceiver` and paints them on a JavaFX `Canvas`.
- Input forwarding uses the existing `InputForwarder` (AWT `Robot`) and is shared between P2P and relay control paths.

## Configuration
- Server endpoints and port ranges are read from `src/main/resources/config.properties` (same keys as the Swing build).
- Screen quality presets: `WAN_SAFE`, `WAN_ULTRA`, `LAN_HIGH` (selectable from Sessions or Settings panes).
