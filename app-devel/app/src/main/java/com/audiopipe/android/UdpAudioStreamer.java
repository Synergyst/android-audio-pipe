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

    // Pre-allocated buffer to avoid GC churn in sendPacket
    private ByteBuffer packetBuffer = ByteBuffer.allocate(4096);

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
        sendPacket(AudioConfig.TYPE_HANDSHAKE_REQ, null, 0);
        Log.i(TAG, "Handshake request sent.");
    }

    public void sendPing() {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_PING, null, 0);
    }

    public void sendAudio(byte[] audioData, int length) {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_AUDIO, audioData, length);
    }

    private void sendPacket(byte type, byte[] payload, int payloadLen) {
        try {
            int redundantLen = (lastPayload != null) ? lastPayload.length : 0;
            int totalSize = 8 + payloadLen + redundantLen;

            if (totalSize > packetBuffer.capacity()) {
                packetBuffer = ByteBuffer.allocate(totalSize * 2);
            }

            packetBuffer.clear();
            packetBuffer.put(type);
            packetBuffer.put(sessionId);
            packetBuffer.putInt(sequenceNumber++);
            
            if (payload != null && payloadLen > 0) {
                packetBuffer.put(payload, 0, payloadLen);
            }
            if (lastPayload != null) {
                packetBuffer.put(lastPayload);
            }
            
            byte[] packetData = packetBuffer.array();
            DatagramPacket packet = new DatagramPacket(
                packetData, 
                totalSize, 
                serverAddress, 
                serverPort
            );
            
            socket.send(packet);
            
            if (payload != null && payloadLen > 0) {
                // Store current payload for next packet's FEC
                // Note: We still allocate this once per packet, but it's smaller than the full packet buffer
                lastPayload = new byte[payloadLen];
                System.arraycopy(payload, 0, lastPayload, 0, payloadLen);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error sending UDP packet: " + e.getMessage());
        }
    }

    public void sendDisconnect() {
        if (!isStreaming || socket == null) return;
        sendPacket(AudioConfig.TYPE_DISCONNECT, null, 0);
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
