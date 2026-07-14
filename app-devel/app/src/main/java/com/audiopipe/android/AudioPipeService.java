package com.audiopipe.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.IOException;

public class AudioPipeService extends Service implements AudioCaptureEngine.AudioDataListener {
    private static final String TAG = "AudioPipeService";
    public static final String ACTION_STOP = "STOP_SERVICE";
    
    private AudioCaptureEngine captureEngine;
    private UdpAudioStreamer udpStreamer;
    private UdpAudioReceiver udpReceiver;
    private AudioPlaybackEngine playbackEngine;
    
    private String serverIp = "192.168.168.12"; 
    private int serverPort = 12345;
    private int localListenPort = 12346;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop action received. Shutting down service...");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent.hasExtra("SERVER_IP")) {
            serverIp = intent.getStringExtra("SERVER_IP");
        }
        if (intent.hasExtra("SERVER_PORT")) {
            serverPort = intent.getIntExtra("SERVER_PORT", 12345);
        }

        Log.i(TAG, "Starting Audio Pipe Service for target: " + serverIp + ":" + serverPort);
        
        // 1. MUST start foreground immediately on the main thread to avoid crash
        startForegroundService();
        
        // 2. Move all heavy lifting to a background thread
        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing components in background thread...");
                RootOptimizer.optimizeSystem();
                
                playbackEngine = new AudioPlaybackEngine(this);
                playbackEngine.start();
                
                udpStreamer = new UdpAudioStreamer();
                udpStreamer.start(serverIp, serverPort);
                
                udpReceiver = new UdpAudioReceiver(localListenPort, data -> {
                    if (playbackEngine != null) {
                        playbackEngine.playAudio(data);
                    }
                });
                udpReceiver.start();
                
                captureEngine = new AudioCaptureEngine(this);
                captureEngine.start();
                
                Log.i(TAG, "Service operational. Outbound: " + serverIp + ":" + serverPort + " | Inbound: " + localListenPort);
            } catch (IOException e) {
                Log.e(TAG, "Failed to start audio pipe: " + e.getMessage());
            }
        }, "ServiceInitThread").start();

        return START_STICKY;
    }

    @Override
    public void onAudioDataCaptured(byte[] data, int size) {
        if (udpStreamer != null) {
            udpStreamer.sendAudio(data);
        }
    }

    private void startForegroundService() {
        String channelId = "audio_pipe_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId, "Audio Pipe Service", 
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("Android Audio Pipe")
                .setContentText("Streaming audio to Linux PC...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroying service and releasing resources...");
        if (captureEngine != null) captureEngine.stop();
        if (udpStreamer != null) udpStreamer.stop();
        if (udpReceiver != null) udpReceiver.stop();
        if (playbackEngine != null) playbackEngine.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
