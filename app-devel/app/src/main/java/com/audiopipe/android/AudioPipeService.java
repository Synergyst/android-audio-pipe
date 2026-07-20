package com.audiopipe.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioPipeService extends Service implements AudioCaptureEngine.AudioDataListener, UdpAudioReceiver.AudioReceiverListener {
    private static final String TAG = "AudioPipeService";
    public static final String ACTION_STOP = "STOP_SERVICE";
    public static final String ACTION_OPEN_APP = "OPEN_APP";
    public static final String ACTION_UPDATE_ROUTING = "UPDATE_ROUTING";
    public static final String ACTION_UPDATE_SAMPLE_RATE = "UPDATE_SAMPLE_RATE";
    
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
    private BufferPool bufferPool;
    
    private String serverIp = "192.168.168.12"; 
    private int serverPort = 12345;
    private int localListenPort = 12346;
    private int controlPort = 12347;

    private AudioConfig.RoutingMode routingMode = AudioConfig.RoutingMode.SPEAKERPHONE;
    private boolean useAecNr = false;
    private int currentNetworkSampleRate = AudioConfig.SAMPLE_RATE;

    private ServiceState currentState = ServiceState.DISCONNECTED;
    private long lastPacketSeen = 0;
    private long lastHandshakeSent = 0;
    private static final long HANDSHAKE_RETRY_INTERVAL = 2000; // 2 seconds
    private static final long CONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private static final long RECONNECT_INTERVAL_MS = 5000; // 5 seconds
    private byte[] currentSessionId = AudioConfig.SESSION_ID;

    // TRUE ZERO-ALLOCATION BUFFERS
    private static final int MAX_BUF_SIZE = 16384;
    private byte[] captureResampleBuffer = new byte[MAX_BUF_SIZE];
    private byte[] playbackResampleBuffer = new byte[MAX_BUF_SIZE];
    private byte[] playbackRedundantBuffer = new byte[MAX_BUF_SIZE];
    private short[] captureShortBuffer = new short[MAX_BUF_SIZE / 2];
    private short[] playbackShortBuffer = new short[MAX_BUF_SIZE / 2];
    private short[] playbackRedundantShortBuffer = new short[MAX_BUF_SIZE / 2];

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop action received. Shutting down service...");
            updateState(ServiceState.DISCONNECTED);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_UPDATE_ROUTING.equals(intent.getAction())) {
            if (intent.hasExtra("ROUTING_MODE")) {
                routingMode = (AudioConfig.RoutingMode) intent.getSerializableExtra("ROUTING_MODE");
                configureAudioRouting();
                Log.i(TAG, "Routing mode updated via Intent: " + routingMode);
            }
            return START_STICKY;
        }

        if (ACTION_UPDATE_SAMPLE_RATE.equals(intent.getAction())) {
            if (intent.hasExtra("SAMPLE_RATE")) {
                int newRate = intent.getIntExtra("SAMPLE_RATE", AudioConfig.SAMPLE_RATE);
                new Thread(() -> updateNetworkSampleRate(newRate), "SampleRateUpdateThread").start();
            }
            return START_STICKY;
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
        if (intent.hasExtra("SAMPLE_RATE")) {
            currentNetworkSampleRate = intent.getIntExtra("SAMPLE_RATE", AudioConfig.SAMPLE_RATE);
        }

        Log.i(TAG, "Starting Audio Pipe Service for target: " + serverIp + ":" + serverPort + " [Mode: " + routingMode + ", AEC/NR: " + useAecNr + ", NetRate: " + currentNetworkSampleRate + "Hz]");
        
        startForegroundSetup();
        
        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing components...");
                RootOptimizer.optimizeSystem();
                
                bufferPool = new BufferPool(100, AudioConfig.BUFFER_SIZE);
                playbackEngine = new AudioPlaybackEngine(this, bufferPool);
                configureAudioRouting();
                playbackEngine.start();
                
                udpStreamer = new UdpAudioStreamer();
                udpStreamer.start(serverIp, serverPort);
                
                udpReceiver = new UdpAudioReceiver(localListenPort, this, bufferPool);
                udpReceiver.start();
                
                controlServer = new TcpControlServer(controlPort, this);
                controlServer.start();
                
                updateState(ServiceState.CONNECTING);
                
                captureEngine = new AudioCaptureEngine(this, useAecNr);
                captureEngine.start();
                
                // Send handshake after capture starts so the first few audio
                // frames aren't discarded due to CONNECTING state.
                udpReceiver.sendHandshake(serverIp, serverPort);
                lastHandshakeSent = System.currentTimeMillis();
                
                Log.i(TAG, "Service operational. Waiting for handshake response...");
                
                // Start the connection monitor AFTER all components are initialized
                // to prevent race condition where monitor sends handshakes before
                // udpReceiver is ready to listen.
                startConnectionMonitor();
            } catch (IOException e) {
                Log.e(TAG, "Failed to start audio pipe: " + e.getMessage());
                updateState(ServiceState.DISCONNECTED);
            }
        }, "ServiceInitThread").start();

        return START_STICKY;
    }

    private void updateNetworkSampleRate(int newRate) {
        Log.i(TAG, "Updating network sample rate to " + newRate + "Hz");
        this.currentNetworkSampleRate = newRate;
        
        if (playbackEngine != null) {
            playbackEngine.updateSampleRate(newRate);
        }
        if (captureEngine != null) {
            captureEngine.updateSampleRate(newRate);
        }
        
        if (udpReceiver != null) {
            udpReceiver.sendNegotiationRequest(serverIp, serverPort, newRate);
        }
    }

    private synchronized void updateState(ServiceState newState) {
        if (this.currentState != newState) {
            Log.i(TAG, "State transition: " + currentState + " -> " + newState);
            this.currentState = newState;
            // Run on main thread — Samsung One UI can crash/ANR when foreground
            // notifications or broadcasts are updated from background threads.
            new Handler(Looper.getMainLooper()).post(() -> {
                updateNotification();
                Intent intent = new Intent("com.audiopipe.android.STATE_CHANGED");
                intent.putExtra("state", newState.name());
                sendBroadcast(intent);
            });
        }
    }

    public ServiceState getCurrentState() {
        return currentState;
    }

    @Override
    public void onAudioDataCaptured(byte[] data, int size) {
        if (udpStreamer != null && currentState == ServiceState.CONNECTED) {
            // TRUE ZERO-ALLOCATION: Resample into pre-allocated buffer using pre-allocated shorts
            int actualLen = AudioResampler.resample(data, 0, size, captureShortBuffer, captureResampleBuffer, AudioConfig.SAMPLE_RATE, currentNetworkSampleRate);
            udpStreamer.sendAudio(captureResampleBuffer, actualLen);
        }
    }

    @Override
    public void onAudioDataReceived(int sequence, byte[] data, byte[] redundantData) {
        if (playbackEngine != null) {
            // TRUE ZERO-ALLOCATION: Resample into pre-allocated buffer using pre-allocated shorts
            int actualLen = AudioResampler.resample(data, 0, data.length, playbackShortBuffer, playbackResampleBuffer, currentNetworkSampleRate, AudioConfig.SAMPLE_RATE);
            
            byte[] resampledRedundant = null;
            if (redundantData != null) {
                int redLen = AudioResampler.resample(redundantData, 0, redundantData.length, playbackRedundantShortBuffer, playbackRedundantBuffer, currentNetworkSampleRate, AudioConfig.SAMPLE_RATE);
                
                // Must allocate a new buffer here: the resampled redundant data
                // can be up to MAX_BUF_SIZE bytes, far larger than the 512-byte
                // BufferPool buffers.  PlaybackEngine needs to hold it until
                // playback consumes it.
                resampledRedundant = new byte[redLen];
                System.arraycopy(playbackRedundantBuffer, 0, resampledRedundant, 0, redLen);
            }
            
            // PlaybackEngine.playAudio must handle the length of the buffer
            playbackEngine.playAudio(sequence, playbackResampleBuffer, actualLen, resampledRedundant);
            
            // BUG #2 FIX: data and redundantData were allocated with new byte[] in
            // UdpAudioReceiver (not from the pool). Do NOT release them back to the pool.
            // Releasing non-pool buffers pollutes the pool with stale audio data that
            // will be played or re-sent later, corrupting the audio stream.
            // Let them be garbage collected normally.
            // bufferPool.release(data);  // REMOVED
            // if (redundantData != null) {
            //     bufferPool.release(redundantData);  // REMOVED
            // }
            
            // resampledRedundant was allocated with new byte[], NOT from the pool,
            // so it should NOT be released back to the pool (it would silently be
            // dropped since its size rarely matches the expected 512 bytes).
            // Let it be garbage collected normally.
        }
    }

    @Override
    public void onPacketReceived(byte type, int sequence) {
        // Only update lastPacketSeen on actual audio packets — ping/pong/handshake
        // responses would give a false sense of a live audio connection.
        if (type == AudioConfig.TYPE_AUDIO) {
            lastPacketSeen = System.currentTimeMillis();
        }
        // Note: PONG intentionally does NOT update lastPacketSeen, because the
        // server could respond to pings while the audio path is broken. We only
        // consider audio packets as proof of a live connection.
        
        if (type == AudioConfig.TYPE_HANDSHAKE_RESP) {
            Log.i(TAG, "Handshake response received! Connection established.");
            updateState(ServiceState.CONNECTED);
            if (udpReceiver != null) {
                udpReceiver.sendNegotiationRequest(serverIp, serverPort, currentNetworkSampleRate);
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
        }
        if (playbackEngine != null) {
            playbackEngine.resetSequence();
        }
        if (udpReceiver != null) {
            udpReceiver.setSessionId(sessionId);
        }
    }

    @Override
    public void onNegotiationComplete(int sampleRate) {
        Log.i(TAG, "Negotiated sample rate: " + sampleRate + "Hz");
        this.currentNetworkSampleRate = sampleRate;
        
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

    private void startForegroundSetup() {
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

    private void startConnectionMonitor() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    long now = System.currentTimeMillis();

                    if (currentState == ServiceState.CONNECTING || currentState == ServiceState.CONNECTION_LOST) {
                        long interval = (currentState == ServiceState.CONNECTING) ? HANDSHAKE_RETRY_INTERVAL : RECONNECT_INTERVAL_MS;
                        if (now - lastHandshakeSent > interval) {
                            Log.i(TAG, "Attempting connection/reconnection...");
                            if (udpReceiver != null) {
                                udpReceiver.sendHandshake(serverIp, serverPort);
                                lastHandshakeSent = now;
                            }
                        }
                    }

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
