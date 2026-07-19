package com.audiopipe.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioResampler {
    
    /**
     * Resamples PCM 16-bit mono audio data into a pre-allocated buffer.
     * 
     * @param input The source audio data in bytes.
     * @param inputOffset Offset in input array
     * @param inputLen Length of valid data in input array
     * @param inputSamples Pre-allocated short buffer to avoid allocations
     * @param output The pre-allocated destination buffer in bytes.
     * @param inputRate The sample rate of the input data.
     * @param outputRate The desired sample rate of the output data.
     * @return The number of bytes actually written to the output buffer.
     */
    public static int resample(byte[] input, int inputOffset, int inputLen, short[] inputSamples, byte[] output, int inputRate, int outputRate) {
        if (inputRate == outputRate) {
            int len = Math.min(inputLen, output.length);
            System.arraycopy(input, inputOffset, output, 0, len);
            return len;
        }

        // Use the provided short buffer to avoid allocations
        ByteBuffer.wrap(input, inputOffset, inputLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputSamples, 0, inputLen / 2);

        double ratio = (double) inputRate / outputRate;
        int outputLength = (int) ((inputLen / 2) / ratio);
        
        // Clamp to output buffer size
        int actualOutputLength = Math.min(outputLength, output.length / 2);
        
        for (int i = 0; i < actualOutputLength; i++) {
            double position = i * ratio;
            int index = (int) position;
            double fraction = position - index;

            short sample;
            if (index + 1 < (inputLen / 2)) {
                sample = (short) ((1.0 - fraction) * inputSamples[index] + fraction * inputSamples[index + 1]);
            } else if (index < (inputLen / 2)) {
                sample = inputSamples[index];
            } else {
                sample = 0;
            }
            
            output[i * 2] = (byte) (sample & 0xFF);
            output[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        return actualOutputLength * 2;
    }
}
