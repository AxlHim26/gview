#!/usr/bin/env bash
set -euo pipefail

# Auto-load .env from multiple possible locations
# Priority: 1) Current dir, 2) Script dir, 3) ~/.config/gview-peer/, 4) /etc/gview-peer/
load_env_file() {
  local env_file=""
  
  # Check current directory
  if [[ -f ".env" ]]; then
    env_file=".env"
  # Check script directory
  elif [[ -f "${0%/*}/.env" ]]; then
    env_file="${0%/*}/.env"
  # Check user config directory
  elif [[ -f "${HOME}/.config/gview-peer/.env" ]]; then
    env_file="${HOME}/.config/gview-peer/.env"
  # Check system config directory (for .deb install)
  elif [[ -f "/etc/gview-peer/.env" ]]; then
    env_file="/etc/gview-peer/.env"
  fi
  
  if [[ -n "$env_file" ]]; then
    echo "Loading environment from: $env_file"
    # Export variables from .env file
    # set -a enables automatic export of all variables
    set -a
    # Create temp file with cleaned content (remove comments, empty lines, and 'export' keyword)
    local temp_env=$(mktemp)
    grep -v '^[[:space:]]*#' "$env_file" | grep -v '^[[:space:]]*$' | sed 's/^[[:space:]]*export[[:space:]]*//' > "$temp_env"
    source "$temp_env"
    rm -f "$temp_env"
    set +a
  fi
}

# Load .env if variables not set
if [[ -z "${AUTH_KEY:-}" || -z "${SERVER_TS_ADDR:-}" ]]; then
  load_env_file
fi

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

# Find config.properties in multiple locations
CONFIG_FILE=""
if [[ -f "src/main/resources/config.properties" ]]; then
  CONFIG_FILE="src/main/resources/config.properties"
elif [[ -f "/opt/gview-peer/lib/config.properties" ]]; then
  CONFIG_FILE="/opt/gview-peer/lib/config.properties"
elif [[ -f "/etc/gview-peer/config.properties" ]]; then
  CONFIG_FILE="/etc/gview-peer/config.properties"
fi

echo "=========================================="
echo "Tailscale Auto-Installer (macOS/Linux)"
echo "=========================================="

# CRITICAL: Check for Wayland on Linux (Robot won't work)
if [[ "$(uname)" == "Linux" ]]; then
  SESSION_TYPE="${XDG_SESSION_TYPE:-unknown}"
  WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-}"
  
  if [[ "$SESSION_TYPE" == "wayland" ]] || [[ -n "$WAYLAND_DISPLAY" ]]; then
    echo ""
    echo "=========================================="
    echo "⚠️  WAYLAND DETECTED - WILL NOT WORK"
    echo "=========================================="
    echo "Java Robot requires X11 to control mouse/keyboard."
    echo "Your current session: $SESSION_TYPE"
    echo ""
    echo "To fix:"
    echo "  1. Logout from Ubuntu"
    echo "  2. At login screen, click gear icon (⚙️)"
    echo "  3. Select 'Ubuntu on Xorg' (X11)"
    echo "  4. Login and run this script again"
    echo "=========================================="
    echo ""
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
      exit 1
    fi
  else
    echo "✓ X11 session detected (DISPLAY=${DISPLAY})"
  fi
fi

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
if [[ -n "${CONFIG_FILE}" ]] && [[ -f "${CONFIG_FILE}" ]]; then
  echo "Updating peer configuration..."
  # Create backup
  cp "${CONFIG_FILE}" "${CONFIG_FILE}.bak" 2>/dev/null || true
  # Update config (try Linux sed first, then macOS sed)
  sed -i.bak \
    -e "s|^idserver.url=.*|idserver.url=http://${SERVER_TS_ADDR}:8080|" \
    -e "s|^idserver.ws.url=.*|idserver.ws.url=ws://${SERVER_TS_ADDR}:8080/ws|" \
    "${CONFIG_FILE}" 2>/dev/null || \
  sed -i '' \
    -e "s|^idserver.url=.*|idserver.url=http://${SERVER_TS_ADDR}:8080|" \
    -e "s|^idserver.ws.url=.*|idserver.ws.url=ws://${SERVER_TS_ADDR}:8080/ws|" \
    "${CONFIG_FILE}" 2>/dev/null || true
  echo "Config updated: ${CONFIG_FILE}"
  echo "Backup saved: ${CONFIG_FILE}.bak"
else
  echo "Note: config.properties not found (this is OK for .deb installs)"
  echo "Configuration will be read from environment variables or .env file"
  echo "Server should be at: http://${SERVER_TS_ADDR}:8080"
fi

echo "=========================================="
echo "Setup complete!"
echo "Your Tailscale IP: ${TS_IP}"
echo "Server should be at: ${SERVER_TS_ADDR}"
echo "Run 'tailscale status' to see all devices."
echo "=========================================="
