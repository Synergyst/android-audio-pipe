#!/bin/bash
# Start Audio Stack Script

echo "[1/5] Killing existing PulseAudio instances..."
killall -9 pulseaudio 2>/dev/null

# Ensure the root user runtime directory exists
mkdir -p /run/user/0/pulse

echo "[2/5] Starting PulseAudio daemon..."
# If --null is passed, we skip PulseAudio entirely
if [[ "$*" == *"--null"* ]]; then
    echo "NULL MODE DETECTED: Skipping PulseAudio initialization."
else
    export PULSE_RUNTIME_PATH=/run/user/0/pulse

    # Force daemon initialization directly to bypass client.conf autospawn locks
    pulseaudio -D --exit-idle-time=-1 2>/dev/null
    sleep 2

    # Force all subsequent client tools (pactl & audio_pipe_server) to target this exact socket
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

echo "[4/5] Creating virtual devices..."
if [[ "$*" == *"--null"* ]]; then
    echo "Bypassing virtual device creation (Null Mode)."
else
    pactl unload-module module-null-sink 2>/dev/null
    pactl unload-module module-remap-source 2>/dev/null

    SINK_ID=$(pactl load-module module-null-sink sink_name=AndroidPipe sink_properties=device.description='AndroidAudioPipe_Speaker')
    echo "Created Sink (ID: $SINK_ID)"
    SOURCE_ID=$(pactl load-module module-remap-source source_name=AndroidPipeMic source_properties=device.description='AndroidAudioPipe_Mic' master=AndroidPipe.monitor)
    echo "Created Source (ID: $SOURCE_ID)"
fi

echo "[5/5] Launching Audio Pipe Server..."
/root/android-audio-pipe/server-devel/audio_pipe_server "$@"
