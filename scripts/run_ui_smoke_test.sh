#!/usr/bin/env bash
set -euo pipefail

# Simple smoke test runner for the JavaFX UI. This does not automate user input
# but provides quick commands for manual verification.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "Building without tests..."
mvn -q -DskipTests package

echo "Starting JavaFX client (press Ctrl+C to exit)..."
mvn -q -DskipTests javafx:run

echo "Manual checks to perform:"
echo "1) Register a peer or connect with an existing ID from the Identity panel."
echo "2) Connect to another peer (or a loopback peer) and verify remote frames render."
echo "3) Move/click/keyboard on the canvas to ensure input messages are delivered."
echo "4) Toggle Relay mode by dropping P2P connectivity and confirm frames continue via relay."
echo "5) Switch light/dark themes from the menu and ensure the UI redraws correctly."
