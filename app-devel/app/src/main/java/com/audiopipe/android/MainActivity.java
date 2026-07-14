package com.audiopipe.android;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.graphics.Color;
import android.view.Gravity;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AudioPipe_UI";
    private static final int REQUEST_RECORD_AUDIO = 200;

    private EditText ipInput;
    private EditText portInput;
    private Button toggleButton;
    private TextView statusText;
    private Button settingsButton;
    
    private boolean isServiceRunning = false;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupUI();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            performAsyncRootCheck();
        }
    }

    private void setupUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(50, 50, 50, 50);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("AUDIO PIPE");
        title.setTextSize(28);
        title.setTextColor(Color.GREEN);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 50);
        root.addView(title);

        ipInput = new EditText(this);
        ipInput.setHint("Server IP");
        ipInput.setTextColor(Color.WHITE);
        ipInput.setHintTextColor(Color.GRAY);
        ipInput.setText("192.168.168.12");
        root.addView(ipInput);

        portInput = new EditText(this);
        portInput.setHint("Server Port");
        portInput.setTextColor(Color.WHITE);
        portInput.setHintTextColor(Color.GRAY);
        portInput.setText("12345");
        root.addView(portInput);

        toggleButton = new Button(this);
        toggleButton.setText("START STREAM");
        toggleButton.setEnabled(false);
        toggleButton.setOnClickListener(v -> handleToggle());
        root.addView(toggleButton);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTextColor(Color.LTGRAY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setText("Initializing...");
        statusText.setPadding(0, 50, 0, 0);
        root.addView(statusText);

        settingsButton = new Button(this);
        settingsButton.setText("Open App Settings");
        settingsButton.setVisibility(View.GONE);
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        });
        root.addView(settingsButton);

        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                performAsyncRootCheck();
            } else {
                statusText.setText("PERMISSION DENIED\nMicrophone access is required.");
                statusText.setTextColor(Color.RED);
                settingsButton.setVisibility(View.VISIBLE);
            }
        }
    }

    private void performAsyncRootCheck() {
        statusText.setText("Checking Root Access...");
        backgroundExecutor.execute(() -> {
            boolean rooted = RootUtils.isRooted();
            runOnUiThread(() -> {
                if (rooted) {
                    statusText.setText("Root: GRANTED ✅");
                    statusText.setTextColor(Color.GREEN);
                    toggleButton.setEnabled(true);
                } else {
                    statusText.setText("Root: NOT FOUND ❌");
                    statusText.setTextColor(Color.YELLOW);
                    toggleButton.setEnabled(true);
                }
            });
        });
    }

    private void handleToggle() {
        if (!isServiceRunning) {
            startAudioPipe();
        } else {
            stopAudioPipe();
        }
    }

    private void startAudioPipe() {
        try {
            String ip = ipInput.getText().toString().trim();
            int port = Integer.parseInt(portInput.getText().toString().trim());

            Intent intent = new Intent(this, AudioPipeService.class);
            intent.putExtra("SERVER_IP", ip);
            intent.putExtra("SERVER_PORT", port);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            isServiceRunning = true;
            toggleButton.setText("STOP STREAM");
            toggleButton.setBackgroundColor(Color.RED);
            statusText.setText("SERVICE ACTIVE 🚀\nTarget: " + ip + ":" + port);
            statusText.setTextColor(Color.GREEN);
        } catch (Exception e) {
            statusText.setText("ERROR: " + e.getMessage());
            statusText.setTextColor(Color.RED);
        }
    }

    private void stopAudioPipe() {
        Intent intent = new Intent(this, AudioPipeService.class);
        intent.setAction("STOP_SERVICE");
        startService(intent);

        isServiceRunning = false;
        toggleButton.setText("START STREAM");
        toggleButton.setBackgroundColor(Color.LTGRAY);
        statusText.setText("Service Stopped.");
        statusText.setTextColor(Color.LTGRAY);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
    }
}
