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

    public void start(String ip, int port) throws IOException {
        this.serverAddress = InetAddress.getByName(ip);
        this.serverPort = port;
        this.socket = new DatagramSocket();
        this.isStreaming = true;
        Log.i(TAG, "UDP Streamer started. Target: " + ip + ":" + port);
    }

    public void sendAudio(byte[] audioData) {
        if (!isStreaming || socket == null) return;

        try {
            // Header: 4 bytes for sequence number
            ByteBuffer buffer = ByteBuffer.allocate(4 + audioData.length);
            buffer.putInt(sequenceNumber++);
            buffer.put(audioData);
            
            byte[] packetData = buffer.array();
            DatagramPacket packet = new DatagramPacket(
                packetData, 
                packetData.length, 
                serverAddress, 
                serverPort
            );
            
            socket.send(packet);
        } catch (IOException e) {
            Log.e(TAG, "Error sending UDP packet: " + e.getMessage());
        }
    }

    public void stop() {
        isStreaming = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        Log.i(TAG, "UDP Streamer stopped.");
    }
}
