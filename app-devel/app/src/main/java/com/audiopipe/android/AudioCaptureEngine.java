package com.audiopipe.android;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;

public class AudioCaptureEngine implements Runnable {
    private static final String TAG = "AudioCaptureEngine";
    
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private AudioDataListener listener;
    private Thread captureThread;
    private boolean useAecNr;

    public interface AudioDataListener {
        void onAudioDataCaptured(byte[] data, int size);
    }

    public AudioCaptureEngine(AudioDataListener listener, boolean useAecNr) {
        this.listener = listener;
        this.useAecNr = useAecNr;
    }

    public void start() throws IOException {
        int minBufferSize = AudioRecord.getMinBufferSize(
                AudioConfig.SAMPLE_RATE, 
                AudioConfig.CHANNEL_CONFIG, 
                AudioConfig.AUDIO_FORMAT
        );

        if (minBufferSize < 0) {
            throw new IOException("Invalid audio configuration");
        }

        // Use the larger of minBufferSize or our predefined buffer size
        int bufferSize = Math.max(minBufferSize, AudioConfig.BUFFER_SIZE);

        // VOICE_COMMUNICATION typically enables AEC and Noise Suppression on supported devices
        int audioSource = useAecNr ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC;

        audioRecord = new AudioRecord(
                audioSource,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("AudioRecord failed to initialize");
        }

        isRecording = true;
        audioRecord.startRecording();
        
        captureThread = new Thread(this);
        captureThread.setPriority(Thread.MAX_PRIORITY); // High priority for audio capture
        captureThread.start();
        Log.i(TAG, "Audio capture started (Source: " + audioSource + ", AEC/NR: " + useAecNr + ").");
    }

    public void stop() {
        isRecording = false;
        if (captureThread != null) {
            captureThread.interrupt();
        }
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        Log.i(TAG, "Audio capture stopped.");
    }

    @Override
    public void run() {
        byte[] buffer = new byte[AudioConfig.BUFFER_SIZE];
        
        while (isRecording) {
            try {
                int readSize = audioRecord.read(buffer, 0, buffer.length);
                if (readSize > 0 && listener != null) {
                    // Use the pre-allocated buffer instead of creating a new array every time
                    // to reduce GC pressure. The listener is responsible for immediate use or copying.
                    listener.onAudioDataCaptured(buffer, readSize);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading audio data: " + e.getMessage());
                isRecording = false;
            }
        }
    }
}
