#!/bin/bash

# Load environment variables from .env file (kept in /root for security)
ENV_FILE="/root/.env"

if [ -f "$ENV_FILE" ]; then
    export $(grep -v '^#' "$ENV_FILE" | xargs)
else
    echo "Error: .env file not found at $ENV_FILE"
    exit 1
fi

if [ -z "$PIN" ]; then
    echo "Error: PIN not found in $ENV_FILE"
    exit 1
fi

# Check for lock flag
LOCK_AT_END=false
if [[ "$1" == "--lock" ]]; then
    LOCK_AT_END=true
    echo "Lock flag detected. Screen will be turned off at the end."
fi

# --- CONFIGURATION ---
LANDSCAPE_ROTATION=1
PACKAGE_NAME="com.audiopipe.android"
MAIN_ACTIVITY="com.audiopipe.android/.MainActivity"
PIN_PAD_WINDOW="Bouncer"
# ---------------------

# 1. Check Wakefulness
WAKE_STATUS=$(adb shell dumpsys power | grep "mWakefulness=" | cut -d'=' -f2 | tr -d '\r')

if [ "$WAKE_STATUS" != "Awake" ]; then
    echo "Device is $WAKE_STATUS. Waking up..."
    adb shell input keyevent 224
    sleep 1.5
fi

# 2. Unlock Sequence with Safety Verification
echo "Performing unlock swipe..."
adb shell input swipe 500 1500 500 500 100
sleep 1.0

CURRENT_FOCUS=$(adb shell dumpsys window | grep "mCurrentFocus" | grep "$PIN_PAD_WINDOW")

if [ ! -z "$CURRENT_FOCUS" ]; then
    echo "PIN pad detected ($PIN_PAD_WINDOW). Sending PIN..."
    adb shell input text "$PIN"
    sleep 1.0
else
    echo "PIN pad not detected. Device might already be unlocked. Skipping PIN entry."
fi

# 3. Restart Audio Pipe App
echo "Restarting Audio Pipe app..."
adb shell am force-stop "$PACKAGE_NAME"
sleep 0.5
adb shell am start -n "$MAIN_ACTIVITY"
sleep 2.0 # Give the app time to load and connect to service

# 4. Handle Rotation (FINAL STEP)
echo "Forcing orientation to locked landscape..."
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation $LANDSCAPE_ROTATION
adb shell settings put secure user_rotation $LANDSCAPE_ROTATION

# 5. Optional Lock
if [ "$LOCK_AT_END" = true ]; then
    echo "Locking device (turning off screen)..."
    adb shell input keyevent 26
fi

echo "Process complete."
