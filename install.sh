#!/usr/bin/env bash
set -euo pipefail

# Tailscale auth key - MUST be set via environment variable
if [[ -z "${AUTH_KEY:-}" ]]; then
  echo "Error: AUTH_KEY environment variable not set"
  echo "Usage: AUTH_KEY=tskey-xxx SERVER_TS_ADDR=100.x.y.z ./install.sh"
  exit 1
fi

# Server Tailscale IP - MUST be set via environment variable
if [[ -z "${SERVER_TS_ADDR:-}" ]]; then
  echo "Error: SERVER_TS_ADDR environment variable not set"
  echo "Usage: AUTH_KEY=tskey-xxx SERVER_TS_ADDR=100.x.y.z ./install.sh"
  exit 1
fi

CONFIG_FILE="src/main/resources/config.properties"

echo "=========================================="
echo "Tailscale Auto-Installer (macOS/Linux)"
echo "=========================================="

# Check if Tailscale is installed
if ! command -v tailscale >/dev/null 2>&1; then
  echo "Tailscale not found. Installing..."
  if [[ "$(uname)" == "Darwin" ]]; then
    if ! command -v brew >/dev/null 2>&1; then
      echo "Error: Homebrew not installed. Install from https://brew.sh"
      exit 1
    fi
    brew install tailscale || { echo "Failed to install Tailscale"; exit 1; }
  else
    # Linux
    curl -fsSL https://tailscale.com/install.sh | sh
  fi
  echo "Tailscale installed successfully."
else
  echo "Tailscale already installed."
fi

# Join tailnet
echo "Joining Tailscale network..."
sudo tailscale up --authkey "${AUTH_KEY}" --accept-routes

# Get this machine's Tailscale IP
TS_IP=$(tailscale ip -4 | head -n1)
echo "Joined tailnet with IP: ${TS_IP}"

# Update peer config if file exists
if [[ -f "${CONFIG_FILE}" ]]; then
  echo "Updating peer configuration..."
  sed -i.bak \
    -e "s|^idserver.url=.*|idserver.url=http://${SERVER_TS_ADDR}:8080|" \
    -e "s|^idserver.ws.url=.*|idserver.ws.url=ws://${SERVER_TS_ADDR}:8080/ws|" \
    "${CONFIG_FILE}"
  echo "Config updated: ${CONFIG_FILE}"
  echo "Backup saved: ${CONFIG_FILE}.bak"
else
  echo "Warning: config file not found at ${CONFIG_FILE}"
  echo "Please update manually with:"
  echo "  idserver.url=http://${SERVER_TS_ADDR}:8080"
  echo "  idserver.ws.url=ws://${SERVER_TS_ADDR}:8080/ws"
fi

echo "=========================================="
echo "Setup complete!"
echo "Your Tailscale IP: ${TS_IP}"
echo "Server should be at: ${SERVER_TS_ADDR}"
echo "Run 'tailscale status' to see all devices."
echo "=========================================="
