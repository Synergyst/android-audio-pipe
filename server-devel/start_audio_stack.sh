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
    # For root, we use a specific environment variable to trick PulseAudio
    # into thinking it's not running as root or to force the socket path.
    export PULSE_RUNTIME_PATH=/run/user/0/pulse
    
    # Try starting in user mode first but as root (this often works if the socket is set)
    pulseaudio --start --exit-idle-time=-1
    sleep 2
fi

echo "[3/5] Verifying PulseAudio connection..."
if [[ "$*" == *"--null"* ]]; then
    echo "PulseAudio check bypassed (Null Mode)."
else
    if ! pactl info > /dev/null 2>&1; then
        echo "ERROR: PulseAudio failed to start. Attempting alternative..."
        # Alternative: start manually in background
        pulseaudio -D --exit-idle-time=-1
        sleep 2
        if ! pactl info > /dev/null 2>&1; then
            echo "CRITICAL ERROR: PulseAudio is unreachable."
            exit 1
        fi
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
