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
        AudioPacket(int sequence, byte[] data) {
            this.sequence = sequence;
            this.data = data;
        }
    }

    // PriorityQueue to handle out-of-order packets
    private final PriorityQueue<AudioPacket> packetBuffer = new PriorityQueue<>(Comparator.comparingInt(p -> p.sequence));
    private final Object bufferLock = new Object();
    
    private int nextExpectedSequence = -1;
    private int currentBufferThreshold = 5; // Initial threshold
    private final int MIN_THRESHOLD = 3;
    private final int MAX_THRESHOLD = 50;
    private final int TARGET_BUFFER_SIZE = 10;
    private final int DRIFT_UPPER_BOUND = 25; // Drop packets if we exceed this to correct clock drift
    
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

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);

        isPlaying = true;
        audioTrack.play();
        
        playbackThread = new Thread(this, "AudioPlaybackThread");
        playbackThread.setPriority(Thread.MAX_PRIORITY);
        playbackThread.start();
        Log.i(TAG, "Audio playback engine started at " + rate + "Hz.");
    }

    public void playAudio(int sequence, byte[] data, byte[] redundantData) {
        if (!isPlaying) return;
        
        synchronized (bufferLock) {
            // Drop packets that are too old
            if (nextExpectedSequence != -1 && sequence < nextExpectedSequence) {
                return; 
            }

            // FEC: If we have redundant data and we're missing the previous packet, fill it in
            if (redundantData != null && nextExpectedSequence != -1 && sequence == nextExpectedSequence + 1) {
                AudioPacket missingPacket = new AudioPacket(nextExpectedSequence, redundantData);
                packetBuffer.offer(missingPacket);
                Log.v(TAG, "FEC: Recovered missing packet " + nextExpectedSequence);
            }

            // Clock Drift Correction
            if (packetBuffer.size() > DRIFT_UPPER_BOUND) {
                AudioPacket dropped = packetBuffer.poll();
                if (dropped != null) {
                    nextExpectedSequence = dropped.sequence + 1;
                    Log.v(TAG, "Clock drift detected: dropping packet " + dropped.sequence + " to catch up. Buffer size: " + packetBuffer.size());
                }
            }

            packetBuffer.offer(new AudioPacket(sequence, data));
            
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
                
                synchronized (bufferLock) {
                    if (packetBuffer.size() < currentBufferThreshold) {
                        bufferLock.wait(10);
                    } else {
                        AudioPacket head = packetBuffer.peek();
                        if (head != null) {
                            if (nextExpectedSequence == -1 || head.sequence == nextExpectedSequence) {
                                AudioPacket p = packetBuffer.poll();
                                dataToPlay = p.data;
                                nextExpectedSequence = p.sequence + 1;
                            } else if (packetBuffer.size() > MAX_THRESHOLD) {
                                AudioPacket p = packetBuffer.poll();
                                dataToPlay = p.data;
                                nextExpectedSequence = p.sequence + 1;
                                Log.v(TAG, "Sequence gap detected, skipping to " + p.sequence);
                            }
                        }
                    }
                }

                if (dataToPlay != null) {
                    if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                        audioTrack.write(dataToPlay, 0, dataToPlay.length);
                    }
                } else {
                    // Adaptive Buffer: If we are starving, increase threshold
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
