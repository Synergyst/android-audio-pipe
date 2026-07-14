package com.audiopipe.android;

import android.util.Log;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class TcpControlServer implements Runnable {
    private static final String TAG = "TcpControlServer";
    
    private final int port;
    private final AudioPipeService service;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private Thread serverThread;

    public TcpControlServer(int port, AudioPipeService service) {
        this.port = port;
        this.service = service;
    }

    public void start() {
        isRunning = true;
        serverThread = new Thread(this, "TcpControlThread");
        serverThread.start();
        Log.i(TAG, "TCP Control Server started on port " + port);
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket: " + e.getMessage());
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
        Log.i(TAG, "TCP Control Server stopped.");
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            while (isRunning) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleClient(clientSocket)).start();
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "Accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not listen on port " + port + ": " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            String line;
            while ((line = in.readLine()) != null) {
                Log.d(TAG, "Received control command: " + line);
                String response = processCommand(line);
                out.println(response);
            }
        } catch (IOException e) {
            Log.e(TAG, "Client handler error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private String processCommand(String cmd) {
        String[] parts = cmd.split(" ");
        String action = parts[0].toUpperCase();
        
        try {
            switch (action) {
                case "SET_ROUTING":
                    if (parts.length < 2) return "ERROR: Missing mode";
                    AudioConfig.RoutingMode mode = AudioConfig.RoutingMode.valueOf(parts[1].toUpperCase());
                    service.setRoutingMode(mode);
                    return "OK: Routing set to " + mode;
                
                case "SET_AEC":
                    if (parts.length < 2) return "ERROR: Missing value";
                    boolean enabled = Boolean.parseBoolean(parts[1]);
                    service.setAecNr(enabled);
                    return "OK: AEC set to " + enabled;
                
                case "SET_SAMPLE_RATE":
                    if (parts.length < 2) return "ERROR: Missing rate";
                    int rate = Integer.parseInt(parts[1]);
                    // We can use the existing negotiation logic
                    service.onNegotiationComplete(rate);
                    return "OK: Sample rate set to " + rate;
                
                case "GET_STATUS":
                    return "STATUS: " + service.getCurrentState();
                
                case "PING":
                    return "PONG";
                
                default:
                    return "ERROR: Unknown command";
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
