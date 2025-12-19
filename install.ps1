# Tailscale Auto-Installer for Windows
# Run as Administrator in PowerShell

$ErrorActionPreference = "Stop"

# Tailscale auth key - MUST be set via environment variable
if (-not $env:AUTH_KEY) {
    Write-Host "Error: AUTH_KEY environment variable not set" -ForegroundColor Red
    Write-Host "Usage: `$env:AUTH_KEY='tskey-xxx'; `$env:SERVER_TS_ADDR='100.x.y.z'; .\install.ps1"
    exit 1
}

# Server Tailscale IP - MUST be set via environment variable
if (-not $env:SERVER_TS_ADDR) {
    Write-Host "Error: SERVER_TS_ADDR environment variable not set" -ForegroundColor Red
    Write-Host "Usage: `$env:AUTH_KEY='tskey-xxx'; `$env:SERVER_TS_ADDR='100.x.y.z'; .\install.ps1"
    exit 1
}

$authKey = $env:AUTH_KEY
$serverTsAddr = $env:SERVER_TS_ADDR
$configFile = "src\main\resources\config.properties"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Tailscale Auto-Installer (Windows)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Check if running as Administrator
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Error: Please run as Administrator" -ForegroundColor Red
    exit 1
}

# Check if Tailscale is installed
if (-not (Get-Command tailscale.exe -ErrorAction SilentlyContinue)) {
    Write-Host "Tailscale not found. Installing via winget..."
    try {
        winget install tailscale.tailscale --accept-source-agreements --accept-package-agreements --silent
        Write-Host "Tailscale installed successfully." -ForegroundColor Green
        # Wait for service to start
        Start-Sleep -Seconds 5
    } catch {
        Write-Host "Failed to install Tailscale. Please install manually from https://tailscale.com/download" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "Tailscale already installed." -ForegroundColor Green
}

# Join tailnet
Write-Host "Joining Tailscale network..."
tailscale up --authkey $authKey --accept-routes

# Get this machine's Tailscale IP
$tsIp = (tailscale ip -4).Split([Environment]::NewLine)[0]
Write-Host "Joined tailnet with IP: $tsIp" -ForegroundColor Green

# Update peer config if file exists
if (Test-Path $configFile) {
    Write-Host "Updating peer configuration..."
    $content = Get-Content $configFile
    $updated = $content | ForEach-Object {
        $_ -replace '^idserver\.url=.*', "idserver.url=http://${serverTsAddr}:8080" `
           -replace '^idserver\.ws\.url=.*', "idserver.ws.url=ws://${serverTsAddr}:8080/ws"
    }
    # Backup original
    Copy-Item $configFile "${configFile}.bak"
    $updated | Set-Content $configFile
    Write-Host "Config updated: $configFile" -ForegroundColor Green
    Write-Host "Backup saved: ${configFile}.bak" -ForegroundColor Green
} else {
    Write-Host "Warning: config file not found at $configFile" -ForegroundColor Yellow
    Write-Host "Please update manually with:"
    Write-Host "  idserver.url=http://${serverTsAddr}:8080"
    Write-Host "  idserver.ws.url=ws://${serverTsAddr}:8080/ws"
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Setup complete!" -ForegroundColor Green
Write-Host "Your Tailscale IP: $tsIp"
Write-Host "Server should be at: $serverTsAddr"
Write-Host "Run 'tailscale status' to see all devices."
Write-Host "==========================================" -ForegroundColor Cyan
