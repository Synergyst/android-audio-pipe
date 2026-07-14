#!/bin/bash
# Start Audio Stack Script

echo "[1/5] Killing existing PulseAudio instances..."
killall -9 pulseaudio 2>/dev/null

echo "[2/5] Starting PulseAudio daemon..."
pulseaudio --start
sleep 2

echo "[3/5] Verifying PulseAudio connection..."
if ! pactl info > /dev/null 2>&1; then
    echo "ERROR: PulseAudio failed to start or is unreachable."
    exit 1
fi
echo "PulseAudio is UP."

echo "[4/5] Creating virtual devices..."
# Remove existing to avoid duplicates
pactl unload-module module-null-sink 2>/dev/null
pactl unload-module module-remap-source 2>/dev/null

SINK_ID=$(pactl load-module module-null-sink sink_name=AndroidPipe sink_properties=device.description='AndroidAudioPipe_Speaker')
echo "Created Sink (ID: $SINK_ID)"
SOURCE_ID=$(pactl load-module module-remap-source source_name=AndroidPipeMic source_properties=device.description='AndroidAudioPipe_Mic' master=AndroidPipe.monitor)
echo "Created Source (ID: $SOURCE_ID)"

echo "[5/5] Launching Audio Pipe Server..."
/root/android-audio-pipe/server-devel/audio_pipe_server
