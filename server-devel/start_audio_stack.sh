#!/bin/bash
# Start Audio Stack Script

echo "[1/5] Killing existing PulseAudio instances..."
killall -9 pulseaudio 2>/dev/null

# Ensure the root user runtime directory exists
mkdir -p /run/user/0/pulse

echo "[2/5] Starting PulseAudio daemon..."
if [[ "$*" == *"--null"* ]]; then
    echo "NULL MODE DETECTED: Skipping PulseAudio initialization."
else
    export PULSE_RUNTIME_PATH=/run/user/0/pulse
    pulseaudio -D --exit-idle-time=-1 2>/dev/null
    sleep 2
    export PULSE_SERVER=unix:/run/user/0/pulse/native
fi

echo "[3/5] Verifying PulseAudio connection..."
if [[ "$*" == *"--null"* ]]; then
    echo "PulseAudio check bypassed (Null Mode)."
else
    if ! pactl info > /dev/null 2>&1; then
        echo "CRITICAL ERROR: PulseAudio is unreachable at $PULSE_SERVER"
        exit 1
    fi
    echo "PulseAudio is UP."
fi

echo "[4/5] Preparing Virtual Devices..."
# Virtual device creation is now handled by the server binary to support --mode

echo "[5/5] Launching Audio Pipe Server..."
/root/android-audio-pipe/server-devel/audio_pipe_server "$@"
