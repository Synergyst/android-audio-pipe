package com.audiopipe.android;

import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class UdpAudioStreamer {
    private static final String TAG = "UdpAudioStreamer";
    
    private DatagramSocket socket;
    private InetAddress serverAddress;
    private int serverPort;
    private int sequenceNumber = 0;
    private boolean isStreaming = false;
    private byte[] sessionId = AudioConfig.SESSION_ID;
    private byte[] lastPayload = null;

    public void setSessionId(byte[] newSessionId) {
        if (newSessionId != null) {
            this.sessionId = newSessionId;
        }
    }

    public void start(String ip, int port) throws IOException {
        this.serverAddress = InetAddress.getByName(ip);
        this.serverPort = port;
        this.socket = new DatagramSocket();
        this.isStreaming = true;
        Log.i(TAG, "UDP Streamer started. Target: " + ip + ":" + port);
    }

    public void sendHandshake() {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_HANDSHAKE_REQ, new byte[0]);
        Log.i(TAG, "Handshake request sent.");
    }

    public void sendPing() {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_PING, new byte[0]);
    }

    public void sendAudio(byte[] audioData) {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_AUDIO, audioData);
    }

    private void sendPacket(byte type, byte[] payload) {
        try {
            // Header: type (1) + session_id (3) + sequence (4) = 8 bytes
            // We append the previous payload for FEC: [Header][CurrentPayload][PreviousPayload]
            int payloadLen = (payload != null) ? payload.length : 0;
            int redundantLen = (lastPayload != null) ? lastPayload.length : 0;
            
            ByteBuffer buffer = ByteBuffer.allocate(8 + payloadLen + redundantLen);
            buffer.put(type);
            buffer.put(sessionId);
            buffer.putInt(sequenceNumber++);
            
            if (payload != null) {
                buffer.put(payload);
            }
            if (lastPayload != null) {
                buffer.put(lastPayload);
            }
            
            byte[] packetData = buffer.array();
            DatagramPacket packet = new DatagramPacket(
                packetData, 
                packetData.length, 
                serverAddress, 
                serverPort
            );
            
            socket.send(packet);
            
            // Store current payload for next packet's FEC
            if (payload != null) {
                lastPayload = payload;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error sending UDP packet: " + e.getMessage());
        }
    }

    public void sendDisconnect() {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_DISCONNECT, new byte[0]);
        Log.i(TAG, "Disconnect packet sent.");
    }

    public void stop() {
        sendDisconnect();
        isStreaming = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        Log.i(TAG, "UDP Streamer stopped.");
    }
}
