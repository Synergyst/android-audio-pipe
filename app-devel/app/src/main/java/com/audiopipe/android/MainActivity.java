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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AudioPipe_UI";
    private static final int REQUEST_RECORD_AUDIO = 200;

    private EditText ipInput;
    private EditText portInput;
    private android.widget.Spinner routingSpinner;
    private android.widget.Spinner rateSpinner;
    private android.widget.CheckBox aecCheckBox;
    private Button toggleButton;
    private TextView statusText;
    private Button settingsButton;
    private Button testConnectionButton;
    
    private boolean isServiceRunning = false;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra("state");
            runOnUiThread(() -> {
                statusText.setText("Service State: " + state);
                if ("CONNECTED".equals(state)) {
                    statusText.setTextColor(Color.GREEN);
                    toggleButton.setText("STOP STREAM");
                    toggleButton.setBackgroundColor(Color.RED);
                    toggleButton.setTextColor(Color.WHITE);
                } else if ("DISCONNECTED".equals(state)) {
                    statusText.setTextColor(Color.LTGRAY);
                    toggleButton.setText("START STREAM");
                    toggleButton.setBackgroundColor(Color.parseColor("#444444"));
                    toggleButton.setTextColor(Color.WHITE);
                } else {
                    statusText.setTextColor(Color.YELLOW);
                }
            });
        }
    };

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
        root.setBackgroundColor(Color.parseColor("#121212"));

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
        ipInput.setHintTextColor(Color.parseColor("#888888"));
        ipInput.setBackgroundColor(Color.parseColor("#2C2C2C"));
        ipInput.setTextColor(Color.WHITE);
        ipInput.setText("192.168.168.12");
        root.addView(ipInput);

        portInput = new EditText(this);
        portInput.setHint("Server Port");
        portInput.setTextColor(Color.WHITE);
        portInput.setHintTextColor(Color.parseColor("#888888"));
        portInput.setBackgroundColor(Color.parseColor("#2C2C2C"));
        portInput.setTextColor(Color.WHITE);
        portInput.setText("12345");
        root.addView(portInput);

        // --- Settings Section ---
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("AUDIO SETTINGS");
        settingsTitle.setTextColor(Color.GREEN);
        settingsTitle.setGravity(Gravity.CENTER);
        settingsTitle.setPadding(0, 30, 0, 10);
        root.addView(settingsTitle);

        // Routing Spinner
        routingSpinner = new android.widget.Spinner(this);
        routingSpinner.setBackgroundColor(Color.parseColor("#2C2C2C"));
        AudioConfig.RoutingMode[] modes = AudioConfig.RoutingMode.values();
        android.widget.ArrayAdapter<AudioConfig.RoutingMode> routingAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, modes);
        routingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routingSpinner.setAdapter(routingAdapter);
        root.addView(routingSpinner);

        // Sample Rate Spinner
        rateSpinner = new android.widget.Spinner(this);
        rateSpinner.setBackgroundColor(Color.parseColor("#2C2C2C"));
        int[] rates = AudioConfig.SUPPORTED_SAMPLE_RATES;
        String[] rateStrings = new String[rates.length];
        for(int i=0; i<rates.length; i++) rateStrings[i] = rates[i] + " Hz";
        
        android.widget.ArrayAdapter<String> rateAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, rateStrings);
        rateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rateSpinner.setAdapter(rateAdapter);
        root.addView(rateSpinner);

        aecCheckBox = new android.widget.CheckBox(this);
        aecCheckBox.setText("Enable AEC/NR (Voice Comm Mode)");
        aecCheckBox.setTextColor(Color.WHITE);
        root.addView(aecCheckBox);
        // ------------------------------

        testConnectionButton = new Button(this);
        testConnectionButton.setText("TEST CONNECTION");
        testConnectionButton.setTextColor(Color.WHITE);
        testConnectionButton.setBackgroundColor(Color.parseColor("#333333"));
        testConnectionButton.setOnClickListener(v -> performConnectionTest());
        root.addView(testConnectionButton);

        toggleButton = new Button(this);
        toggleButton.setText("START STREAM");
        toggleButton.setTextColor(Color.WHITE);
        toggleButton.setBackgroundColor(Color.parseColor("#444444"));
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
        loadPreferences();
    }

    private void performConnectionTest() {
        String ip = ipInput.getText().toString().trim();
        int port = Integer.parseInt(portInput.getText().toString().trim());
        
        statusText.setText("Testing connection to " + ip + "...");
        backgroundExecutor.execute(() -> {
            try {
                java.net.InetAddress address = java.net.InetAddress.getByName(ip);
                boolean reachable = address.isReachable(2000);
                
                runOnUiThread(() -> {
                    if (reachable) {
                        statusText.setText("Server Reachable ✅");
                        statusText.setTextColor(Color.GREEN);
                    } else {
                        statusText.setText("Server Unreachable ❌");
                        statusText.setTextColor(Color.RED);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Connection Test Failed: " + e.getMessage());
                    statusText.setTextColor(Color.RED);
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                performAsyncRootCheck();
            } else {
                statusText.setText("PERMISSION DENIED\\nMicrophone access is required.");
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
                
                // Auto-connect if not already running
                if (!isServiceRunning) {
                    startAudioPipe();
                }
            });
        });
    }

    private void handleToggle() {
        if (!isServiceRunning) {
            startAudioPipe();
        } else {
            if (routingSpinner.getSelectedItemPosition() != -1) {
                AudioConfig.RoutingMode mode = (AudioConfig.RoutingMode) routingSpinner.getSelectedItem();
                Intent intent = new Intent(this, AudioPipeService.class);
                intent.setAction("UPDATE_ROUTING");
                intent.putExtra("ROUTING_MODE", mode);
                startService(intent);
            }
            stopAudioPipe();
        }
    }

    private void startAudioPipe() {
        try {
            savePreferences();
            String ip = ipInput.getText().toString().trim();
            int port = Integer.parseInt(portInput.getText().toString().trim());
            AudioConfig.RoutingMode mode = (AudioConfig.RoutingMode) routingSpinner.getSelectedItem();
            boolean useAec = aecCheckBox.isChecked();
            int sampleRate = AudioConfig.SUPPORTED_SAMPLE_RATES[rateSpinner.getSelectedItemPosition()];

            Intent intent = new Intent(this, AudioPipeService.class);
            intent.putExtra("SERVER_IP", ip);
            intent.putExtra("SERVER_PORT", port);
            intent.putExtra("ROUTING_MODE", mode);
            intent.putExtra("USE_AEC_NR", useAec);
            intent.putExtra("SAMPLE_RATE", sampleRate);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            isServiceRunning = true;
            toggleButton.setText("STOP STREAM");
            toggleButton.setBackgroundColor(Color.RED);
            statusText.setText("Starting Service...");
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
        toggleButton.setBackgroundColor(Color.parseColor("#444444"));
        statusText.setText("Service Stopped.");
        statusText.setTextColor(Color.LTGRAY);
    }

    private void loadPreferences() {
        android.content.SharedPreferences prefs = getSharedPreferences("AudioPipePrefs", android.content.Context.MODE_PRIVATE);
        
        ipInput.setText(prefs.getString("pref_ip", "192.168.168.12"));
        portInput.setText(prefs.getString("pref_port", "12345"));
        
        int modeOrdinal = prefs.getInt(AudioConfig.PREF_ROUTING_MODE, AudioConfig.RoutingMode.SPEAKERPHONE.ordinal());
        routingSpinner.setSelection(modeOrdinal);
        
        int rateIndex = prefs.getInt(AudioConfig.PREF_SAMPLE_RATE, 2); // Default to 44100 (index 2)
        rateSpinner.setSelection(rateIndex);
        
        aecCheckBox.setChecked(prefs.getBoolean(AudioConfig.PREF_AEC_NR, false));
    }

    private void savePreferences() {
        android.content.SharedPreferences.Editor editor = getSharedPreferences("AudioPipePrefs", android.content.Context.MODE_PRIVATE).edit();
        
        editor.putString("pref_ip", ipInput.getText().toString().trim());
        editor.putString("pref_port", portInput.getText().toString().trim());
        
        AudioConfig.RoutingMode mode = (AudioConfig.RoutingMode) routingSpinner.getSelectedItem();
        editor.putInt(AudioConfig.PREF_ROUTING_MODE, mode.ordinal());
        
        int rateIndex = rateSpinner.getSelectedItemPosition();
        editor.putInt(AudioConfig.PREF_SAMPLE_RATE, rateIndex);
        
        editor.putBoolean(AudioConfig.PREF_AEC_NR, aecCheckBox.isChecked());
        editor.apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(stateReceiver, new IntentFilter("com.audiopipe.android.STATE_CHANGED"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(stateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
    }
}
