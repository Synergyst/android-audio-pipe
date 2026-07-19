#!/bin/bash
# Start Audio Stack Script

# Prevent X11/XCB timeouts by unsetting DISPLAY for this process and its children
unset DISPLAY

echo "[1/3] Starting Audio Pipe Server..."

# The server binary now auto-detects root vs standard user and handles
# PulseAudio startup/runtime-dir internally. No bash-level PulseAudio
# management needed.

echo "[2/3] Launching Audio Pipe Server..."
/root/android-audio-pipe/server-devel/audio_pipe_user "$@"
