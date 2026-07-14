package com.audiopipe.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.io.IOException;

public class AudioPipeService extends Service implements AudioCaptureEngine.AudioDataListener, UdpAudioReceiver.AudioReceiverListener {
    private static final String TAG = "AudioPipeService";
    public static final String ACTION_STOP = "STOP_SERVICE";
    public static final String ACTION_OPEN_APP = "OPEN_APP";
    
    public enum ServiceState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CONNECTION_LOST
    }

    private AudioCaptureEngine captureEngine;
    private UdpAudioStreamer udpStreamer;
    private UdpAudioReceiver udpReceiver;
    private AudioPlaybackEngine playbackEngine;
    
    private String serverIp = "192.168.168.12"; 
    private int serverPort = 12345;
    private int localListenPort = 12346;

    private ServiceState currentState = ServiceState.DISCONNECTED;
    private long lastPacketSeen = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop action received. Shutting down service...");
            updateState(ServiceState.DISCONNECTED);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_OPEN_APP.equals(intent.getAction())) {
            Intent appIntent = new Intent(this, MainActivity.class);
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(appIntent);
            return START_STICKY;
        }

        if (intent.hasExtra("SERVER_IP")) {
            serverIp = intent.getStringExtra("SERVER_IP");
        }
        if (intent.hasExtra("SERVER_PORT")) {
            serverPort = intent.getIntExtra("SERVER_PORT", 12345);
        }

        Log.i(TAG, "Starting Audio Pipe Service for target: " + serverIp + ":" + serverPort);
        
        startForegroundService();
        
        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing components...");
                RootOptimizer.optimizeSystem();
                
                playbackEngine = new AudioPlaybackEngine(this);
                playbackEngine.start();
                
                udpStreamer = new UdpAudioStreamer();
                udpStreamer.start(serverIp, serverPort);
                
                udpReceiver = new UdpAudioReceiver(localListenPort, this);
                udpReceiver.start();
                
                updateState(ServiceState.CONNECTING);
                // FIX: Send handshake from the bound receiver socket, not the streamer
                udpReceiver.sendHandshake(serverIp, serverPort);
                
                captureEngine = new AudioCaptureEngine(this);
                captureEngine.start();
                
                Log.i(TAG, "Service operational. Waiting for handshake response...");
            } catch (IOException e) {
                Log.e(TAG, "Failed to start audio pipe: " + e.getMessage());
                updateState(ServiceState.DISCONNECTED);
            }
        }, "ServiceInitThread").start();

        return START_STICKY;
    }

    private synchronized void updateState(ServiceState newState) {
        if (this.currentState != newState) {
            Log.i(TAG, "State transition: " + currentState + " -> " + newState);
            this.currentState = newState;
            updateNotification();
            
            Intent intent = new Intent("com.audiopipe.android.STATE_CHANGED");
            intent.putExtra("state", newState.name());
            sendBroadcast(intent);
        }
    }

    public ServiceState getCurrentState() {
        return currentState;
    }

    @Override
    public void onAudioDataCaptured(byte[] data, int size) {
        if (udpStreamer != null && currentState == ServiceState.CONNECTED) {
            udpStreamer.sendAudio(data);
        }
    }

    @Override
    public void onAudioDataReceived(byte[] data) {
        if (playbackEngine != null) {
            playbackEngine.playAudio(data);
        }
    }

    @Override
    public void onPacketReceived(byte type, int sequence) {
        lastPacketSeen = System.currentTimeMillis();
        
        if (type == AudioConfig.TYPE_HANDSHAKE_RESP) {
            Log.i(TAG, "Handshake response received! Connection established.");
            updateState(ServiceState.CONNECTED);
        } else if (type == AudioConfig.TYPE_PONG) {
            if (currentState == ServiceState.CONNECTION_LOST) {
                updateState(ServiceState.CONNECTED);
            }
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

        updateNotification();
    }

    private void updateNotification() {
        Notification.Builder builder;
        String channelId = "audio_pipe_channel";
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent stopIntent = new Intent(this, AudioPipeService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent openIntent = new Intent(this, AudioPipeService.class);
        openIntent.setAction(ACTION_OPEN_APP);
        PendingIntent openPendingIntent = PendingIntent.getService(this, 1, openIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        String statusText = "Status: " + currentState;
        if (currentState == ServiceState.CONNECTED) statusText = "Streaming Active ✅";
        else if (currentState == ServiceState.CONNECTING) statusText = "Connecting... ⏳";
        else if (currentState == ServiceState.CONNECTION_LOST) statusText = "Connection Lost ❌";

        Notification notification = builder
                .setContentTitle("Android Audio Pipe")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Open App", openPendingIntent)
                .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroying service...");
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
