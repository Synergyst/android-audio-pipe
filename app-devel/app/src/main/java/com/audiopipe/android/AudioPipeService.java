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
import java.nio.ByteBuffer;

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
    private TcpControlServer controlServer;
    
    private String serverIp = "192.168.168.12"; 
    private int serverPort = 12345;
    private int localListenPort = 12346;
    private int controlPort = 12347;

    private AudioConfig.RoutingMode routingMode = AudioConfig.RoutingMode.SPEAKERPHONE;
    private boolean useAecNr = false;

    private ServiceState currentState = ServiceState.DISCONNECTED;
    private long lastPacketSeen = 0;
    private long lastHandshakeSent = 0;
    private static final long HANDSHAKE_RETRY_INTERVAL = 2000; // 2 seconds
    private static final long CONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private static final long RECONNECT_INTERVAL_MS = 5000; // 5 seconds
    private byte[] currentSessionId = AudioConfig.SESSION_ID;

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
        if (intent.hasExtra("ROUTING_MODE")) {
            routingMode = (AudioConfig.RoutingMode) intent.getSerializableExtra("ROUTING_MODE");
        }
        if (intent.hasExtra("USE_AEC_NR")) {
            useAecNr = intent.getBooleanExtra("USE_AEC_NR", false);
        }

        Log.i(TAG, "Starting Audio Pipe Service for target: " + serverIp + ":" + serverPort + " [Mode: " + routingMode + ", AEC/NR: " + useAecNr + "]");
        
        startForegroundService();
        
        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing components...");
                RootOptimizer.optimizeSystem();
                
                playbackEngine = new AudioPlaybackEngine(this);
                configureAudioRouting();
                playbackEngine.start();
                
                udpStreamer = new UdpAudioStreamer();
                udpStreamer.start(serverIp, serverPort);
                
                udpReceiver = new UdpAudioReceiver(localListenPort, this);
                udpReceiver.start();
                
                // Start TCP Control Server for reliable command delivery
                controlServer = new TcpControlServer(controlPort, this);
                controlServer.start();
                
                updateState(ServiceState.CONNECTING);
                udpReceiver.sendHandshake(serverIp, serverPort);
                // REMOVED: udpReceiver.sendNegotiationRequest(serverIp, serverPort);
                lastHandshakeSent = System.currentTimeMillis();
                
                captureEngine = new AudioCaptureEngine(this, useAecNr);
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
    public void onAudioDataReceived(int sequence, byte[] data, byte[] redundantData) {
        if (playbackEngine != null) {
            playbackEngine.playAudio(sequence, data, redundantData);
        }
    }

    @Override
    public void onPacketReceived(byte type, int sequence) {
        lastPacketSeen = System.currentTimeMillis();
        
        if (type == AudioConfig.TYPE_HANDSHAKE_RESP) {
            Log.i(TAG, "Handshake response received! Connection established.");
            updateState(ServiceState.CONNECTED);
            // Trigger negotiation AFTER handshake success to ensure correct session ID
            if (udpReceiver != null) {
                udpReceiver.sendNegotiationRequest(serverIp, serverPort);
            }
        } else if (type == AudioConfig.TYPE_PONG) {
            if (currentState == ServiceState.CONNECTION_LOST) {
                updateState(ServiceState.CONNECTED);
            }
        }
    }

    @Override
    public void onSessionAssigned(byte[] sessionId) {
        Log.i(TAG, "Session ID assigned by server: " + bytesToHex(sessionId));
        this.currentSessionId = sessionId;
        if (udpStreamer != null) {
            udpStreamer.setSessionId(sessionId);
                if (playbackEngine != null) {
            playbackEngine.resetSequence();
        }
    }
        if (udpReceiver != null) {
            udpReceiver.setSessionId(sessionId);
        }
    }

    @Override
    public void onNegotiationComplete(int sampleRate) {
        Log.i(TAG, "Negotiated sample rate: " + sampleRate + "Hz");
        if (playbackEngine != null) {
            playbackEngine.updateSampleRate(sampleRate);
        }
        if (captureEngine != null) {
            captureEngine.updateSampleRate(sampleRate);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
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
        
        // Start connection monitor and retry timer
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    long now = System.currentTimeMillis();

                    // 1. Handle Initial Connection / Reconnection attempts
                    if (currentState == ServiceState.CONNECTING || currentState == ServiceState.CONNECTION_LOST) {
                        long interval = (currentState == ServiceState.CONNECTING) ? HANDSHAKE_RETRY_INTERVAL : RECONNECT_INTERVAL_MS;
                        if (now - lastHandshakeSent > interval) {
                            Log.i(TAG, "Attempting connection/reconnection...");
                            if (udpReceiver != null) {
                                udpReceiver.sendHandshake(serverIp, serverPort);
                                udpReceiver.sendNegotiationRequest(serverIp, serverPort);
                                lastHandshakeSent = now;
                            }
                        }
                    }

                    // 2. Monitor for Connection Loss (Heartbeat)
                    if (currentState == ServiceState.CONNECTED) {
                        if (udpStreamer != null) {
                            udpStreamer.sendPing();
                        }
                        if (now - lastPacketSeen > CONNECTION_TIMEOUT_MS) {
                            Log.w(TAG, "No packets received for " + CONNECTION_TIMEOUT_MS + "ms. Connection lost!");
                            updateState(ServiceState.CONNECTION_LOST);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "ConnectionMonitorThread").start();
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

    private void configureAudioRouting() {
        android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
        Log.i(TAG, "Configuring routing mode: " + routingMode);
        
        switch (routingMode) {
            case SPEAKERPHONE:
                audioManager.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
                break;
            case EARPIECE:
                audioManager.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(false);
                break;
            case NORMAL:
            default:
                audioManager.setMode(android.media.AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
                break;
        }
    }

    // Control methods for TCP server
    public void setRoutingMode(AudioConfig.RoutingMode mode) {
        this.routingMode = mode;
        configureAudioRouting();
        Log.i(TAG, "Routing mode updated via TCP: " + mode);
    }

    public void setAecNr(boolean enabled) {
        this.useAecNr = enabled;
        if (captureEngine != null) {
            captureEngine.updateAecNr(enabled);
        }
        Log.i(TAG, "AEC/NR updated via TCP: " + enabled);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroying service...");
        
        android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
        audioManager.setMode(android.media.AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);
        
        if (captureEngine != null) captureEngine.stop();
        if (udpStreamer != null) udpStreamer.stop();
        if (udpReceiver != null) udpReceiver.stop();
        if (playbackEngine != null) playbackEngine.stop();
        if (controlServer != null) controlServer.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
