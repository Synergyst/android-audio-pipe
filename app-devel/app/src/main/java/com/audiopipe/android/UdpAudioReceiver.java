package com.audiopipe.android;

import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

public class UdpAudioReceiver implements Runnable {
    private static final String TAG = "UdpAudioReceiver";
    
    private DatagramSocket socket;
    private int port;
    private boolean isListening = false;
    private AudioReceiverListener listener;
    
    public interface AudioReceiverListener {
        void onAudioDataReceived(byte[] data);
    }

    public UdpAudioReceiver(int port, AudioReceiverListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public void start() throws IOException {
        this.socket = new DatagramSocket(port);
        this.isListening = true;
        new Thread(this, "UdpListenerThread").start();
        Log.i(TAG, "UDP Receiver started on port " + port + ". Jitter buffer bypassed.");
    }

    @Override
    public void run() {
        byte[] incomingBuffer = new byte[AudioConfig.BUFFER_SIZE + 100];
        while (isListening) {
            try {
                DatagramPacket packet = new DatagramPacket(incomingBuffer, incomingBuffer.length);
                socket.receive(packet);
                byte[] data = packet.getData();
                int length = packet.getLength();
                if (length > 4) {
                    byte[] audioPayload = new byte[length - 4];
                    System.arraycopy(data, 4, audioPayload, 0, length - 4);
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
