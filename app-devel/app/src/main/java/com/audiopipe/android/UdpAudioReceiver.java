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
    
    public interface AudioReceiverListener {
        void onAudioDataReceived(byte[] data);
        void onPacketReceived(byte type, int sequence);
    }

    public UdpAudioReceiver(int port, AudioReceiverListener listener) {
        this.port = port;
        this.listener = listener;
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
            
            // Header: type (1) + session_id (3) + sequence (4) = 8 bytes
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.put(AudioConfig.TYPE_HANDSHAKE_REQ);
            buffer.put(AudioConfig.SESSION_ID);
            buffer.putInt(0); // Sequence 0 for handshake
            
            byte[] data = buffer.array();
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
            Log.i(TAG, "Handshake request sent from bound port " + this.port + " to " + ip + ":" + port);
        } catch (IOException e) {
            Log.e(TAG, "Error sending handshake: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        byte[] incomingBuffer = new byte[AudioConfig.BUFFER_SIZE + 128];
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
                
                boolean sessionMatch = true;
                for (int i = 0; i < 3; i++) {
                    if (session[i] != AudioConfig.SESSION_ID[i]) {
                        sessionMatch = false;
                        break;
                    }
                }
                if (!sessionMatch) {
                    Log.v(TAG, "Ignored packet from unknown session");
                    continue;
                }

                int sequence = bb.getInt();

                if (listener != null) {
                    listener.onPacketReceived(type, sequence);
                }

                if (type == AudioConfig.TYPE_AUDIO) {
                    byte[] audioPayload = new byte[length - 8];
                    bb.get(audioPayload);
                    if (listener != null) {
                        listener.onAudioDataReceived(audioPayload);
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
