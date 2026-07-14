package com.audiopipe.android;

import android.util.Log;
import java.io.IOException;

public class RootOptimizer {
    private static final String TAG = "RootOptimizer";

    public static void optimizeSystem() {
        if (!RootUtils.isRooted()) {
            Log.w(TAG, "Root access not available. Skipping system optimizations.");
            return;
        }

        Log.i(TAG, "Applying root-level optimizations...");

        try {
            RootUtils.startRootSession();

            String[] governorPaths = {
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
                "/sys/devices/system/cpu/cpu1/cpufreq/scaling_governor"
            };
            
            boolean governorSet = false;
            for (String path : governorPaths) {
                try {
                    RootUtils.runAsRoot("echo performance > " + path);
                    Log.i(TAG, "CPU governor set to performance at " + path);
                    governorSet = true;
                } catch (Exception e) {
                    Log.v(TAG, "Governor path not found or failed: " + path);
                }
            }
            if (!governorSet) Log.w(TAG, "Could not find a valid CPU governor path to optimize.");

            try {
                RootUtils.runAsRoot("sysctl -w net.ipv4.tcp_low_latency=1");
                RootUtils.runAsRoot("sysctl -w net.core.rmem_max=16777216");
                RootUtils.runAsRoot("sysctl -w net.core.wmem_max=16777216");
                Log.i(TAG, "Network latency parameters tuned.");
            } catch (IOException e) {
                Log.e(TAG, "Failed to tune network parameters: " + e.getMessage());
            }

            try {
                String pid = String.valueOf(android.os.Process.myPid());
                RootUtils.runAsRoot("renice -n -20 -p " + pid);
                Log.i(TAG, "Process priority set to -20 (Highest).");
            } catch (IOException e) {
                Log.e(TAG, "Failed to set process priority: " + e.getMessage());
            }

        } catch (IOException e) {
            Log.e(TAG, "Critical error starting root session: " + e.getMessage());
        } finally {
            RootUtils.stopRootSession();
        }
    }
}
