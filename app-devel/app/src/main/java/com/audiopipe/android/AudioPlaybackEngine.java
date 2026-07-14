package com.audiopipe.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioPlaybackEngine implements Runnable {
    private static final String TAG = "AudioPlaybackEngine";
    
    private AudioTrack audioTrack;
    private boolean isPlaying = false;
    private Thread playbackThread;
    // Increased queue size for jitter buffering
    private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>(200);
    private final Context context;
    private int currentSampleRate = AudioConfig.SAMPLE_RATE;

    public AudioPlaybackEngine(Context context) {
        this.context = context;
    }

    public void start() {
        startWithRate(currentSampleRate);
    }

    public void updateSampleRate(int newRate) {
        if (this.currentSampleRate == newRate) return;
        Log.i(TAG, "Updating sample rate to " + newRate);
        this.currentSampleRate = newRate;
        stop();
        start();
    }

    private void startWithRate(int rate) {
        int minBufferSize = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioConfig.AUDIO_FORMAT
        );

        int bufferSize = Math.max(minBufferSize, AudioConfig.BUFFER_SIZE);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioConfig.AUDIO_FORMAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize");
            return;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);

        isPlaying = true;
        audioTrack.play();
        
        playbackThread = new Thread(this, "AudioPlaybackThread");
        playbackThread.setPriority(Thread.MAX_PRIORITY);
        playbackThread.start();
        Log.i(TAG, "Audio playback engine started at " + rate + "Hz.");
    }

    public void playAudio(byte[] data) {
        if (!isPlaying) return;
        // Simple jitter buffer logic: wait until queue has a few packets before starting
        // to absorb network jitter.
        if (playbackQueue.size() < 5) {
            // We'll let the playback thread handle the wait
        }
        if (!playbackQueue.offer(data)) {
            Log.w(TAG, "Playback queue overflow, dropping audio frame");
        }
    }

    public void stop() {
        isPlaying = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        playbackQueue.clear();
        
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        
        Log.i(TAG, "Audio playback engine stopped.");
    }

    @Override
    public void run() {
        while (isPlaying) {
            try {
                // Implement a small initial buffering delay to stabilize playback
                if (playbackQueue.size() < 3) {
                    Thread.sleep(10);
                    continue;
                }
                
                byte[] data = playbackQueue.take();
                if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.write(data, 0, data.length);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error playing audio: " + e.getMessage());
            }
        }
    }
}
