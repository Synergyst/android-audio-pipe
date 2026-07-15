package com.audiopipe.android;

import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class UdpAudioReceiver implements Runnable {
    private static final String TAG = "UdpAudioReceiver";
    
    private DatagramSocket socket;
    private int port;
    private boolean isListening = false;
    private AudioReceiverListener listener;
    private byte[] currentSessionId = AudioConfig.SESSION_ID;
    
    public interface AudioReceiverListener {
        void onAudioDataReceived(int sequence, byte[] data, byte[] redundantData);
        void onPacketReceived(byte type, int sequence);
        void onSessionAssigned(byte[] sessionId);
        void onNegotiationComplete(int sampleRate);
    }

    public UdpAudioReceiver(int port, AudioReceiverListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public void setSessionId(byte[] sessionId) {
        this.currentSessionId = sessionId;
    }

    public void start() throws IOException {
        this.socket = new DatagramSocket(port);
        this.isListening = true;
        new Thread(this, "UdpListenerThread").start();
        Log.i(TAG, "UDP Receiver started on port " + port);
    }

    public void sendHandshake(String ip, int port) {
        if (socket == null) return;
        try {
            InetAddress address = InetAddress.getByName(ip);
            
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.put(AudioConfig.TYPE_HANDSHAKE_REQ);
            buffer.put(AudioConfig.SESSION_ID);
            buffer.putInt(0);
            
            byte[] data = buffer.array();
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
            Log.i(TAG, "Handshake request sent from bound port " + this.port + " to " + ip + ":" + port);
        } catch (IOException e) {
            Log.e(TAG, "Error sending handshake: " + e.getMessage());
        }
    }

    public void sendNegotiationRequest(String ip, int port) {
        if (socket == null) return;
        try {
            InetAddress address = InetAddress.getByName(ip);
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.put(AudioConfig.TYPE_NEGOTIATE);
            buffer.put(AudioConfig.SESSION_ID);
            buffer.putInt(0);
            byte[] data = buffer.array();
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            Log.e(TAG, "Error sending negotiation: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        // Buffer size must accommodate Header + 2 * BUFFER_SIZE
        byte[] incomingBuffer = new byte[8 + (2 * AudioConfig.BUFFER_SIZE) + 128];
        while (isListening) {
            try {
                DatagramPacket packet = new DatagramPacket(incomingBuffer, incomingBuffer.length);
                socket.receive(packet);
                
                int length = packet.getLength();
                if (length < 8) continue; 

                ByteBuffer bb = ByteBuffer.wrap(packet.getData());
                byte type = bb.get();
                
                byte[] session = new byte[3];
                bb.get(session);
                
                if (type != AudioConfig.TYPE_HANDSHAKE_RESP) {
                    boolean sessionMatch = true;
                    for (int i = 0; i < 3; i++) {
                        if (session[i] != currentSessionId[i]) {
                            sessionMatch = false;
                            break;
                        }
                    }
                    if (!sessionMatch) {
                        Log.v(TAG, "Ignored packet from unknown session");
                        continue;
                    }
                }

                int sequence = bb.getInt();

                if (listener != null) {
                    listener.onPacketReceived(type, sequence);
                }

                if (type == AudioConfig.TYPE_HANDSHAKE_RESP) {
                    if (listener != null) {
                        listener.onSessionAssigned(session);
                    }
                } else if (type == AudioConfig.TYPE_NEGOTIATE) {
                    if (length >= 12) {
                        byte[] rateBytes = new byte[4];
                        bb.get(rateBytes);
                        int rate = ByteBuffer.wrap(rateBytes).getInt();
                        if (listener != null) {
                            listener.onNegotiationComplete(rate);
                        }
                    }
                } else if (type == AudioConfig.TYPE_AUDIO) {
                    // FIX: Use the constant BUFFER_SIZE instead of dividing length by 2
                    int availableData = length - 8;
                    int currentLen = Math.min(availableData, AudioConfig.BUFFER_SIZE);
                    
                    byte[] audioPayload = new byte[currentLen];
                    bb.get(audioPayload);
                    
                    byte[] redundantPayload = null;
                    int remainingData = availableData - currentLen;
                    if (remainingData > 0) {
                        int redLen = Math.min(remainingData, AudioConfig.BUFFER_SIZE);
                        redundantPayload = new byte[redLen];
                        bb.get(redundantPayload);
                    }
                    
                    if (listener != null) {
                        listener.onAudioDataReceived(sequence, audioPayload, redundantPayload);
                    }
                }
            } catch (IOException e) {
                if (isListening) {
                    Log.e(TAG, "Error receiving UDP packet: " + e.getMessage());
                }
            }
        }
    }

    public void stop() {
        isListening = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        Log.i(TAG, "UDP Receiver stopped.");
    }
}
