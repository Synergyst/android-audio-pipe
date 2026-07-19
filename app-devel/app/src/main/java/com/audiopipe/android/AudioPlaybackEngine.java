package com.audiopipe.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.content.Context;
import android.util.Log;

import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioPlaybackEngine implements Runnable {
    private static final String TAG = "AudioPlaybackEngine";
    
    private AudioTrack audioTrack;
    private boolean isPlaying = false;
    private Thread playbackThread;
    
    private static class AudioPacket {
        int sequence;
        byte[] data;
        int length;
        AudioPacket(int sequence, byte[] data, int length) {
            this.sequence = sequence;
            this.data = data;
            this.length = length;
        }
    }

    private final PriorityQueue<AudioPacket> packetBuffer = new PriorityQueue<>(Comparator.comparingInt(p -> p.sequence));
    private final Object bufferLock = new Object();
    private BufferPool bufferPool;
    
    private int nextExpectedSequence = -1;
    private int currentBufferThreshold = 10; 
    private final int MIN_THRESHOLD = 10;
    private final int MAX_THRESHOLD = 50;
    private final int TARGET_BUFFER_SIZE = 10;
    private final int DRIFT_UPPER_BOUND = 25; 
    
    private final Context context;
    // Hardware rate is locked to AudioConfig.SAMPLE_RATE (44100)
    private final int hardwareSampleRate = AudioConfig.SAMPLE_RATE;

    public AudioPlaybackEngine(Context context, BufferPool bufferPool) {
        this.context = context;
        this.bufferPool = bufferPool;
    }

    public void start() {
        startWithRate(hardwareSampleRate);
    }

    public void updateSampleRate(int newRate) {
        // Hardware remains at 44.1kHz; resampling is handled in the Service
        Log.i(TAG, "Sample rate preference updated to " + newRate + "Hz (Network rate). Hardware remains at " + hardwareSampleRate + "Hz");
    }

    private void startWithRate(int rate) {
        int minBufferSize = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioConfig.AUDIO_FORMAT
        );

        int bufferSize = minBufferSize * 4;

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

        isPlaying = true;
        audioTrack.play();
        
        playbackThread = new Thread(this, "AudioPlaybackThread");
        playbackThread.setPriority(Thread.MAX_PRIORITY);
        playbackThread.start();
        Log.i(TAG, "Audio playback engine started at " + rate + "Hz.");
    }

    public void playAudio(int sequence, byte[] data, int length, byte[] redundantData) {
        if (!isPlaying) return;
        
        synchronized (bufferLock) {
            if (nextExpectedSequence != -1 && sequence < nextExpectedSequence) {
                return; 
            }

            if (redundantData != null && nextExpectedSequence != -1 && sequence == nextExpectedSequence + 1) {
                // Must allocate a new buffer: redundant data can be up to 16KB
                // (BufferPool only has 512-byte buffers, far too small)
                byte[] redData = new byte[redundantData.length];
                System.arraycopy(redundantData, 0, redData, 0, redundantData.length);
                AudioPacket missingPacket = new AudioPacket(nextExpectedSequence, redData, redundantData.length);
                packetBuffer.offer(missingPacket);
                Log.v(TAG, "FEC: Recovered missing packet " + nextExpectedSequence);
            }

            if (packetBuffer.size() > DRIFT_UPPER_BOUND) {
                AudioPacket dropped = packetBuffer.poll();
                if (dropped != null) {
                    nextExpectedSequence = dropped.sequence + 1;
                    Log.v(TAG, "Clock drift detected: dropping packet " + dropped.sequence + " to catch up. Buffer size: " + packetBuffer.size());
                }
            }

            // Must allocate a new buffer: audio packets can be up to ~16KB
            // (BufferPool only has 512-byte buffers, far too small for audio data)
            byte[] packetData = new byte[length];
            System.arraycopy(data, 0, packetData, 0, length);
            packetBuffer.offer(new AudioPacket(sequence, packetData, length));
            
            if (packetBuffer.size() > MAX_THRESHOLD) {
                currentBufferThreshold = Math.max(MIN_THRESHOLD, currentBufferThreshold - 1);
            }
            
            bufferLock.notifyAll();
        }
    }

    public void resetSequence() {
        synchronized (bufferLock) {
            packetBuffer.clear();
            nextExpectedSequence = -1;
            Log.i(TAG, "Playback sequence reset for new session.");
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
        synchronized (bufferLock) {
            packetBuffer.clear();
            nextExpectedSequence = -1;
        }
        
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        
        Log.i(TAG, "Audio playback engine stopped.");
    }

    @Override
    public void run() {
        while (isPlaying) {
            try {
                byte[] dataToPlay = null;
                int lengthToPlay = 0;
                
                synchronized (bufferLock) {
                    if (packetBuffer.size() < currentBufferThreshold) {
                        bufferLock.wait(10);
                    } else {
                        AudioPacket head = packetBuffer.peek();
                        if (head != null) {
                            if (nextExpectedSequence == -1 || head.sequence == nextExpectedSequence) {
                                AudioPacket p = packetBuffer.poll();
                                dataToPlay = p.data;
                                lengthToPlay = p.length;
                                nextExpectedSequence = p.sequence + 1;
                            } else if (packetBuffer.size() > MAX_THRESHOLD) {
                                AudioPacket p = packetBuffer.poll();
                                dataToPlay = p.data;
                                lengthToPlay = p.length;
                                nextExpectedSequence = p.sequence + 1;
                                Log.v(TAG, "Sequence gap detected, skipping to " + p.sequence);
                            }
                        }
                    }
                }

                if (dataToPlay != null) {
                    if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                        // Ensure track is playing — Android may silently stop playback after idle/silence periods.
                        // Calling play() on an already-playing track is a safe no-op.
                        audioTrack.play();
                        audioTrack.write(dataToPlay, 0, lengthToPlay);
                    }
                    // Release buffer back to pool after playback
                    bufferPool.release(dataToPlay);
                } else {
                    synchronized (bufferLock) {
                        if (packetBuffer.isEmpty()) {
                            currentBufferThreshold = Math.min(MAX_THRESHOLD, currentBufferThreshold + 1);
                        }
                    }
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
