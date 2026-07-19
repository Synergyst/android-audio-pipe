package com.audiopipe.android;

import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class BufferPool {
    private static final String TAG = "BufferPool";
    private final BlockingQueue<byte[]> pool;
    private final int bufferSize;
    // Single shared emergency buffer — reused indefinitely when pool is exhausted.
    // This eliminates the leak that occurred when emergency buffers were allocated
    // on each lease() call and then discarded on release() due to size mismatch.
    private byte[] emergencyBuffer;

    public BufferPool(int capacity, int bufferSize) {
        this.bufferSize = bufferSize;
        this.pool = new ArrayBlockingQueue<>(capacity);
        this.emergencyBuffer = new byte[bufferSize];
        for (int i = 0; i < capacity; i++) {
            pool.offer(new byte[bufferSize]);
        }
        Log.i(TAG, "Initialized BufferPool: capacity=" + capacity + ", size=" + bufferSize);
    }

    public byte[] lease() {
        byte[] buffer = pool.poll();
        if (buffer == null) {
            Log.w(TAG, "BufferPool exhausted! Returning emergency buffer.");
            return emergencyBuffer;
        }
        return buffer;
    }

    public void release(byte[] buffer) {
        if (buffer == null || buffer == emergencyBuffer) return;
        if (buffer.length == bufferSize) {
            pool.offer(buffer);
        } else {
            Log.w(TAG, "Dropping non-matching buffer (len=" + buffer.length + " vs expected " + bufferSize + "). This is likely a bug.");
        }
    }

    public int getBufferSize() {
        return bufferSize;
    }
}
