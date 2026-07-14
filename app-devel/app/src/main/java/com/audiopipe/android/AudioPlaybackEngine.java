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
    private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>(100);
    private final Context context;

    public AudioPlaybackEngine(Context context) {
        this.context = context;
    }

    public void start() {
        int minBufferSize = AudioTrack.getMinBufferSize(
                AudioConfig.SAMPLE_RATE,
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
                        .setSampleRate(AudioConfig.SAMPLE_RATE)
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
        Log.i(TAG, "Audio playback engine started in MEDIA mode.");
    }

    public void playAudio(byte[] data) {
        if (!isPlaying) return;
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
