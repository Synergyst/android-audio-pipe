package com.audiopipe.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioResampler {
    
    // FIR filter coefficients generated for quality resampling
    // 32-tap windowed sinc with Hamming window
    private static final int TAP_COUNT = 32;
    private static final int HALF_TAPS = TAP_COUNT / 2;
    private static final double PI = Math.PI;
    private static final double TWO_PI = 2.0 * PI;
    private static final double CUTOFF_FREQ = 0.48; // Slightly below Nyquist for safety
    
    // Pre-computed sinc values for common ratios (cached for performance)
    private static final double[][] sincCache = new double[20][];
    private static final double[] cacheRatios = new double[20];
    private static final int CACHE_SIZE = 20;
    
    static {
        // Pre-compute Hamming window
        for (int i = 0; i < CACHE_SIZE; i++) {
            cacheRatios[i] = 0.5 + i * 0.05; // Cache ratios 0.5 to 1.45
            sincCache[i] = computeSincFilters(cacheRatios[i]);
        }
    }
    
    private static double[] computeSincFilters(double ratio) {
        double[] coeffs = new double[TAP_COUNT];
        int half = TAP_COUNT / 2;
        // Cutoff must be CUTOFF_FREQ / max(ratio, 1.0) to prevent aliasing
        // For downsampling (ratio > 1): cutoff = CUTOFF_FREQ / ratio (tighter filter)
        // For upsampling (ratio < 1):  cutoff = CUTOFF_FREQ (no additional filtering needed)
        double cutoff = CUTOFF_FREQ / Math.max(ratio, 1.0);
        
        for (int n = 0; n < TAP_COUNT; n++) {
            double x = n - half;
            double window = 0.54 - 0.46 * Math.cos(TWO_PI * n / (TAP_COUNT - 1)); // Hamming
            
            double sinc;
            if (Math.abs(x) < 1e-10) {
                sinc = cutoff;
            } else {
                sinc = Math.sin(PI * cutoff * x) / (PI * x);
            }
            
            coeffs[n] = sinc * window / cutoff;
        }
        return coeffs;
    }
    
    private static double getInterpolatedCoeff(double ratio, int n) {
        // Linear interpolation between cached ratios
        double idx = (ratio - 0.5) / 0.05;
        if (idx < 0) idx = 0;
        if (idx >= CACHE_SIZE - 1) idx = CACHE_SIZE - 2;
        
        int low = (int) idx;
        int high = low + 1;
        double frac = idx - low;
        
        double[] lowCoeffs = sincCache[low];
        double[] highCoeffs = sincCache[high];
        
        return lowCoeffs[n] * (1.0 - frac) + highCoeffs[n] * frac;
    }
    
    /**
     * Resamples PCM 16-bit mono audio data using a high-quality windowed sinc FIR filter.
     * Eliminates the distortion caused by simple linear interpolation.
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
        
        double ratio = (double) inputRate / outputRate;
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            return 0;
        }

        // Use the provided short buffer to avoid allocations
        ByteBuffer.wrap(input, inputOffset, inputLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputSamples, 0, inputLen / 2);

        int inputSamplesCount = inputLen / 2;
        int outputLength = (int) ((inputSamplesCount + HALF_TAPS) / ratio);
        
        // Clamp to output buffer size
        int actualOutputLength = Math.min(outputLength, output.length / 2);
        
        if (actualOutputLength <= 0) return 0;
        
        for (int i = 0; i < actualOutputLength; i++) {
            double position = (i + HALF_TAPS) * ratio - HALF_TAPS;
            int index = (int) Math.floor(position);
            double frac = position - index;
            
            if (index < 0) index = 0;
            if (index + TAP_COUNT > inputSamplesCount) break;
            
            // Compute interpolated sample using windowed sinc FIR filter
            double sum = 0.0;
            for (int n = 0; n < TAP_COUNT; n++) {
                if (index + n >= inputSamplesCount) break;
                double coeff = getInterpolatedCoeff(ratio, n);
                sum += inputSamples[index + n] * coeff;
            }
            
            // Clip to 16-bit range and write in little-endian
            short sample = (short) Math.max(-32768, Math.min(32767, Math.round(sum)));
            output[i * 2] = (byte) (sample & 0xFF);
            output[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        return actualOutputLength * 2;
    }
}
