package com.audiopipe.android;

public class AudioConfig {
    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;
    public static final int BUFFER_SIZE = 1024; // 1KB chunks to fit MTU

    // Packet Types (Must match server.cpp)
    public static final byte TYPE_AUDIO = 0x01;
    public static final byte TYPE_PING = 0x02;
    public static final byte TYPE_PONG = 0x03;
    public static final byte TYPE_STATS = 0x04;
    public static final byte TYPE_HANDSHAKE_REQ = 0x05;
    public static final byte TYPE_HANDSHAKE_RESP = 0x06;
    public static final byte TYPE_NEGOTIATE = 0x07;

    // Session ID (Must match server.cpp)
    public static final byte[] SESSION_ID = { (byte)0xDE, (byte)0xAD, (byte)0xBE };

    // Routing Modes
    public enum RoutingMode {
        NORMAL,         // Standard playback/capture
        SPEAKERPHONE,   // Forced to loudspeaker
        EARPIECE       // Forced to earpiece/headset
    }

    // Pref Keys
    public static final String PREF_ROUTING_MODE = "pref_routing_mode";
    public static final String PREF_AEC_NR = "pref_aec_nr";
}
