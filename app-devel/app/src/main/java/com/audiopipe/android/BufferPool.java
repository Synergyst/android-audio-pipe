package com.audiopipe.android;

import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class BufferPool {
    private static final String TAG = "BufferPool";
    private final BlockingQueue<byte[]> pool;
    private final int bufferSize;

    public BufferPool(int capacity, int bufferSize) {
        this.bufferSize = bufferSize;
        this.pool = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < capacity; i++) {
            pool.offer(new byte[bufferSize]);
        }
        Log.i(TAG, "Initialized BufferPool: capacity=" + capacity + ", size=" + bufferSize);
    }

    public byte[] lease() {
        byte[] buffer = pool.poll();
        if (buffer == null) {
            Log.w(TAG, "BufferPool exhausted! Allocating emergency buffer.");
            return new byte[bufferSize];
        }
        return buffer;
    }

    public void release(byte[] buffer) {
        if (buffer == null) return;
        if (buffer.length != bufferSize) {
            // Don't return buffers of the wrong size to the pool
            return;
        }
        pool.offer(buffer);
    }

    public int getBufferSize() {
        return bufferSize;
    }
}
