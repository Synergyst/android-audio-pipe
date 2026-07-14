package com.audiopipe.android;

import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class RootUtils {
    private static final String TAG = "RootUtils";
    
    private static Process rootProcess;
    private static DataOutputStream rootOutputStream;
    private static BufferedReader rootInputStream;

    public static boolean isRooted() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                int exitValue = process.exitValue();
                if (exitValue == 0) {
                    Log.i(TAG, "Root access confirmed via 'su -c id'.");
                    return true;
                }
                Log.w(TAG, "su -c id exited with error code: " + exitValue);
            } else {
                Log.w(TAG, "Root check timed out - root manager may not have responded");
            }
        } catch (Exception e) {
            Log.e(TAG, "Device not rooted or SU access denied: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return false;
    }

    public static void startRootSession() throws IOException {
        if (rootProcess != null) return;

        rootProcess = Runtime.getRuntime().exec("su");
        rootOutputStream = new DataOutputStream(rootProcess.getOutputStream());
        rootInputStream = new BufferedReader(new InputStreamReader(rootProcess.getInputStream()));
        Log.i(TAG, "Persistent root session started.");
    }

    public static String runAsRoot(String command) throws IOException {
        if (rootProcess == null || rootOutputStream == null) {
            throw new IOException("Root session not started. Call startRootSession() first.");
        }

        rootOutputStream.writeBytes(command + "\n");
        rootOutputStream.flush();
        
        return "Executed: " + command;
    }

    public static void stopRootSession() {
        try {
            if (rootOutputStream != null) {
                rootOutputStream.writeBytes("exit\n");
                rootOutputStream.flush();
                rootOutputStream.close();
            }
            if (rootInputStream != null) {
                rootInputStream.close();
            }
            if (rootProcess != null) {
                rootProcess.destroy();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing root session: " + e.getMessage());
        } finally {
            rootProcess = null;
            rootOutputStream = null;
            rootInputStream = null;
        }
    }
}
