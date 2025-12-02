package com.example.androidclient;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import okhttp3.*;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AndroidClient";
    
    // UI Components
    private EditText etServerIp, etWebSocketPort, etHttpPort;
    private Button btnConnect, btnDisconnect;
    private TextView tvStatus, tvLog, connectionTitle;
    private ScrollView svLog;
    private ProgressBar progressBar;
    private LinearLayout llConnected, llDisconnected;
    private CardView connectionCard;
    
    // Network
    private WebSocket webSocket;
    private OkHttpClient okHttpClient;
    private volatile boolean isConnected = false;
    private final Object connectionLock = new Object();
    private final AtomicLong lastMessageTime = new AtomicLong(0);
    
    // Configuration
    private int webSocketPort = 8765;
    private int httpPort = 8080;
    private String serverIp = "";
    
    // Thread management
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // File transfer
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final Map<String, FileTransferSession> fileSessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    
    // Security
    private static final Pattern IP_PATTERN = Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "ls", "pwd", "whoami", "date", "cat /proc/version",
        "getprop", "dumpsys battery", "pm list packages"
    );
    
    // SharedPreferences
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "AndroidClientPrefs";
    private static final String KEY_SERVER_IP = "server_ip";
    private static final String KEY_WS_PORT = "websocket_port";
    private static final String KEY_HTTP_PORT = "http_port";

    // Static WebSocket listener to prevent memory leaks
    private static class SafeWebSocketListener extends WebSocketListener {
        private final WeakReference<MainActivity> activityRef;
        private final String serverIp;
        private final int webSocketPort;
        
        SafeWebSocketListener(MainActivity activity, String serverIp, int webSocketPort) {
            this.activityRef = new WeakReference<>(activity);
            this.serverIp = serverIp;
            this.webSocketPort = webSocketPort;
        }
        
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            MainActivity activity = activityRef.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.handleWebSocketOpen(webSocket, response);
            }
        }
        
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            MainActivity activity = activityRef.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.handleWebSocketMessage(text);
            }
        }
        
        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            MainActivity activity = activityRef.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.handleWebSocketClosing(code, reason);
            }
        }
        
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            MainActivity activity = activityRef.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.handleWebSocketClosed(code, reason);
            }
        }
        
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            MainActivity activity = activityRef.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.handleWebSocketFailure(t, response);
            }
        }
    }

    private WebSocketListener webSocketListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            initializeApp();
        } catch (Exception e) {
            Log.e(TAG, "App initialization failed", e);
            showFatalError("App initialization failed: " + e.getMessage());
            return;
        }
    }

    private void initializeApp() {
        executor = new ThreadPoolExecutor(
            2, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadFactory() {
                private final AtomicInteger threadCount = new AtomicInteger(1);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "ClientThread-" + threadCount.getAndIncrement());
                    thread.setUncaughtExceptionHandler((t, e) -> {
                        Log.e(TAG, "Uncaught exception in thread " + t.getName(), e);
                        logError("Thread " + t.getName() + " crashed: " + e.getMessage());
                    });
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        setupUI();
        initializeViews();
        setupClickListeners();
        loadSavedSettings();
        
        okHttpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
                
        autoConnectIfSettingsSaved();
    }

    private void showFatalError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Fatal Error: " + message, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void setupUI() {
        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setPadding(50, 50, 50, 50);
        mainScroll.setBackgroundColor(0xFFF5F5F5);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Header
        TextView header = new TextView(this);
        header.setText("Android Remote Client");
        header.setTextSize(24);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setGravity(android.view.Gravity.CENTER);
        header.setTextColor(0xFF333333);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) header.getLayoutParams()).bottomMargin = 30;
        mainLayout.addView(header);

        // Connection Card
        connectionCard = new CardView(this);
        connectionCard.setCardElevation(8);
        connectionCard.setRadius(16);
        connectionCard.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) connectionCard.getLayoutParams()).bottomMargin = 20;

        LinearLayout connectionLayout = new LinearLayout(this);
        connectionLayout.setOrientation(LinearLayout.VERTICAL);
        connectionLayout.setPadding(40, 40, 40, 40);

        connectionTitle = new TextView(this);
        connectionTitle.setText("Server Connection");
        connectionTitle.setTextSize(20);
        connectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        connectionTitle.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) connectionTitle.getLayoutParams()).bottomMargin = 20;
        connectionLayout.addView(connectionTitle);

        // Server IP Input
        etServerIp = new EditText(this);
        etServerIp.setHint("Enter Server IP Address");
        etServerIp.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        etServerIp.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) etServerIp.getLayoutParams()).bottomMargin = 15;
        connectionLayout.addView(etServerIp);

        // Port Inputs in Horizontal Layout
        LinearLayout portLayout = new LinearLayout(this);
        portLayout.setOrientation(LinearLayout.HORIZONTAL);
        portLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // WebSocket Port
        LinearLayout wsPortLayout = new LinearLayout(this);
        wsPortLayout.setOrientation(LinearLayout.VERTICAL);
        wsPortLayout.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1
        ));
        ((LinearLayout.LayoutParams) wsPortLayout.getLayoutParams()).rightMargin = 10;

        TextView wsPortLabel = new TextView(this);
        wsPortLabel.setText("WebSocket Port");
        wsPortLabel.setTextSize(12);
        wsPortLabel.setTextColor(0xFF666666);
        wsPortLayout.addView(wsPortLabel);

        etWebSocketPort = new EditText(this);
        etWebSocketPort.setHint("8765");
        etWebSocketPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etWebSocketPort.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        wsPortLayout.addView(etWebSocketPort);

        // HTTP Port
        LinearLayout httpPortLayout = new LinearLayout(this);
        httpPortLayout.setOrientation(LinearLayout.VERTICAL);
        httpPortLayout.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1
        ));
        ((LinearLayout.LayoutParams) httpPortLayout.getLayoutParams()).leftMargin = 10;

        TextView httpPortLabel = new TextView(this);
        httpPortLabel.setText("HTTP Port");
        httpPortLabel.setTextSize(12);
        httpPortLabel.setTextColor(0xFF666666);
        httpPortLayout.addView(httpPortLabel);

        etHttpPort = new EditText(this);
        etHttpPort.setHint("8080");
        etHttpPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etHttpPort.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        httpPortLayout.addView(etHttpPort);

        portLayout.addView(wsPortLayout);
        portLayout.addView(httpPortLayout);
        connectionLayout.addView(portLayout);

        // Saved settings info text
        TextView savedInfo = new TextView(this);
        savedInfo.setText("Settings will be saved automatically after first connection");
        savedInfo.setTextSize(12);
        savedInfo.setTextColor(0xFF666666);
        savedInfo.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) savedInfo.getLayoutParams()).bottomMargin = 10;
        ((LinearLayout.LayoutParams) savedInfo.getLayoutParams()).topMargin = 10;
        connectionLayout.addView(savedInfo);

        btnConnect = new Button(this);
        btnConnect.setText("Connect to Server");
        btnConnect.setBackgroundColor(0xFF4CAF50);
        btnConnect.setTextColor(0xFFFFFFFF);
        btnConnect.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        connectionLayout.addView(btnConnect);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) progressBar.getLayoutParams()).topMargin = 20;
        progressBar.setVisibility(View.GONE);
        connectionLayout.addView(progressBar);

        connectionCard.addView(connectionLayout);
        mainLayout.addView(connectionCard);

        // Status Card
        CardView statusCard = new CardView(this);
        statusCard.setCardElevation(8);
        statusCard.setRadius(16);
        statusCard.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) statusCard.getLayoutParams()).bottomMargin = 20;

        LinearLayout statusLayout = new LinearLayout(this);
        statusLayout.setOrientation(LinearLayout.VERTICAL);
        statusLayout.setPadding(40, 40, 40, 40);

        TextView statusTitle = new TextView(this);
        statusTitle.setText("Connection Status");
        statusTitle.setTextSize(20);
        statusTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        statusTitle.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) statusTitle.getLayoutParams()).bottomMargin = 20;
        statusLayout.addView(statusTitle);

        tvStatus = new TextView(this);
        tvStatus.setText("🔴 Disconnected");
        tvStatus.setTextSize(18);
        tvStatus.setTextColor(0xFFFF0000);
        tvStatus.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) tvStatus.getLayoutParams()).bottomMargin = 20;
        statusLayout.addView(tvStatus);

        // Connected Layout
        llConnected = new LinearLayout(this);
        llConnected.setOrientation(LinearLayout.HORIZONTAL);
        llConnected.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        llConnected.setVisibility(View.GONE);

        btnDisconnect = new Button(this);
        btnDisconnect.setText("Disconnect");
        btnDisconnect.setBackgroundColor(0xFFF44336);
        btnDisconnect.setTextColor(0xFFFFFFFF);
        btnDisconnect.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        llConnected.addView(btnDisconnect);
        statusLayout.addView(llConnected);

        // Disconnected Layout
        llDisconnected = new LinearLayout(this);
        llDisconnected.setOrientation(LinearLayout.VERTICAL);
        llDisconnected.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView disconnectedText = new TextView(this);
        disconnectedText.setText("Enter server details and click Connect");
        disconnectedText.setTextSize(16);
        disconnectedText.setTextColor(0xFF666666);
        disconnectedText.setGravity(android.view.Gravity.CENTER);
        disconnectedText.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        llDisconnected.addView(disconnectedText);
        statusLayout.addView(llDisconnected);

        statusCard.addView(statusLayout);
        mainLayout.addView(statusCard);

        // Log Card
        CardView logCard = new CardView(this);
        logCard.setCardElevation(8);
        logCard.setRadius(16);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            600
        );
        logParams.weight = 1;
        logCard.setLayoutParams(logParams);

        LinearLayout logLayout = new LinearLayout(this);
        logLayout.setOrientation(LinearLayout.VERTICAL);
        logLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));

        TextView logTitle = new TextView(this);
        logTitle.setText("Activity Log");
        logTitle.setTextSize(20);
        logTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        logTitle.setPadding(40, 40, 40, 40);
        logTitle.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        logLayout.addView(logTitle);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFEEEEEE);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            2
        ));
        logLayout.addView(divider);

        svLog = new ScrollView(this);
        svLog.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        svLog.setPadding(20, 20, 20, 20);

        tvLog = new TextView(this);
        tvLog.setText("Log messages will appear here...\n");
        tvLog.setTextSize(14);
        tvLog.setTextColor(0xFF333333);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        svLog.addView(tvLog);

        logLayout.addView(svLog);
        logCard.addView(logLayout);
        mainLayout.addView(logCard);

        // Footer
        TextView footer = new TextView(this);
        footer.setText("Features: Command Execution • File Transfer • Chat • Screen Control • Call Detection");
        footer.setTextSize(12);
        footer.setTextColor(0xFF666666);
        footer.setGravity(android.view.Gravity.CENTER);
        footer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) footer.getLayoutParams()).topMargin = 30;
        mainLayout.addView(footer);

        mainScroll.addView(mainLayout);
        setContentView(mainScroll);
    }

    private void initializeViews() {
        // Views already initialized in setupUI()
    }

    private void setupClickListeners() {
        btnConnect.setOnClickListener(v -> connectToServer());
        btnDisconnect.setOnClickListener(v -> disconnectFromServer());
    }

    private void loadSavedSettings() {
        String savedIP = sharedPreferences.getString(KEY_SERVER_IP, "");
        int savedWsPort = sharedPreferences.getInt(KEY_WS_PORT, 8765);
        int savedHttpPort = sharedPreferences.getInt(KEY_HTTP_PORT, 8080);
        
        if (!savedIP.isEmpty()) {
            etServerIp.setText(savedIP);
            etWebSocketPort.setText(String.valueOf(savedWsPort));
            etHttpPort.setText(String.valueOf(savedHttpPort));
            logMessage("📁 Saved settings loaded: " + savedIP + ":" + savedWsPort);
        }
    }

    private void saveSettings(String ip, int wsPort, int httpPort) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_SERVER_IP, ip);
        editor.putInt(KEY_WS_PORT, wsPort);
        editor.putInt(KEY_HTTP_PORT, httpPort);
        
        if (!editor.commit()) {
            logError("Failed to save settings to SharedPreferences");
        } else {
            logMessage("💾 Settings saved: " + ip + " (WS:" + wsPort + ", HTTP:" + httpPort + ")");
        }
    }

    private void autoConnectIfSettingsSaved() {
        String savedIP = sharedPreferences.getString(KEY_SERVER_IP, "");
        if (!savedIP.isEmpty()) {
            logMessage("🔄 Auto-connecting to saved settings: " + savedIP);
            mainHandler.postDelayed(() -> {
                if (!isConnected && !isFinishing() && !isDestroyed()) {
                    connectToServer();
                }
            }, 2000);
        }
    }

    private void connectToServer() {
        serverIp = etServerIp.getText().toString().trim();
        String wsPortStr = etWebSocketPort.getText().toString().trim();
        String httpPortStr = etHttpPort.getText().toString().trim();
        
        // Validate IP
        if (serverIp.isEmpty()) {
            showToast("Please enter server IP address");
            return;
        }
        
        if (!isValidIpAddress(serverIp)) {
            showToast("Invalid IP address format");
            return;
        }
        
        // Validate ports
        if (wsPortStr.isEmpty()) {
            webSocketPort = 8765;
        } else {
            try {
                webSocketPort = Integer.parseInt(wsPortStr);
                if (webSocketPort < 1 || webSocketPort > 65535) {
                    showToast("WebSocket port must be between 1-65535");
                    return;
                }
            } catch (NumberFormatException e) {
                showToast("Invalid WebSocket port number");
                return;
            }
        }
        
        if (httpPortStr.isEmpty()) {
            httpPort = 8080;
        } else {
            try {
                httpPort = Integer.parseInt(httpPortStr);
                if (httpPort < 1 || httpPort > 65535) {
                    showToast("HTTP port must be between 1-65535");
                    return;
                }
            } catch (NumberFormatException e) {
                showToast("Invalid HTTP port number");
                return;
            }
        }
        
        // Save settings for future use
        saveSettings(serverIp, webSocketPort, httpPort);
        
        setConnectionState(false);
        showProgress(true);
        logMessage("🔄 Connecting to server: " + serverIp + ":" + webSocketPort);
        
        hideConnectionInputs();
        
        executor.execute(() -> {
            try {
                String webSocketUrl = "ws://" + serverIp + ":" + webSocketPort;
                establishWebSocketConnection(webSocketUrl);
            } catch (Exception e) {
                logError("Connection failed: " + e.getMessage());
                showProgress(false);
                showConnectionInputs();
                setConnectionState(false);
            }
        });
    }

    private void hideConnectionInputs() {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            etServerIp.setVisibility(View.GONE);
            etWebSocketPort.setVisibility(View.GONE);
            etHttpPort.setVisibility(View.GONE);
            connectionTitle.setText("Connected to: " + serverIp + ":" + webSocketPort);
        });
    }

    private void showConnectionInputs() {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            etServerIp.setVisibility(View.VISIBLE);
            etWebSocketPort.setVisibility(View.VISIBLE);
            etHttpPort.setVisibility(View.VISIBLE);
            connectionTitle.setText("Server Connection");
        });
    }

    private void establishWebSocketConnection(String webSocketUrl) {
        synchronized (connectionLock) {
            if (isConnected || isFinishing() || isDestroyed()) {
                logMessage("Already connected or activity finishing, skipping new connection");
                return;
            }
            
            try {
                if (!isValidWebSocketUrl(webSocketUrl)) {
                    throw new IllegalArgumentException("Invalid WebSocket URL");
                }
                
                Request request = new Request.Builder()
                        .url(webSocketUrl)
                        .build();
                
                webSocketListener = new SafeWebSocketListener(this, serverIp, webSocketPort);
                webSocket = okHttpClient.newWebSocket(request, webSocketListener);
                
                lastMessageTime.set(System.currentTimeMillis());
                
            } catch (Exception e) {
                logError("WebSocket connection error: " + e.getMessage());
                showProgress(false);
                showConnectionInputs();
                setConnectionState(false);
            }
        }
    }

    private boolean isValidWebSocketUrl(String url) {
        try {
            URI uri = new URI(url);
            return "ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // Handler methods for SafeWebSocketListener
    void handleWebSocketOpen(WebSocket webSocket, Response response) {
        if (isFinishing() || isDestroyed()) return;
        
        logMessage("✅ WebSocket connected successfully");
        setConnectionState(true);
        showProgress(false);
        startConnectionHealthCheck();
        
        try {
            JSONObject auth = new JSONObject();
            auth.put("username", "Android_Client_" + getDeviceId());
            auth.put("device_model", android.os.Build.MODEL);
            auth.put("android_version", android.os.Build.VERSION.RELEASE);
            auth.put("type", "android_client");
            auth.put("websocket_port", webSocketPort);
            auth.put("http_port", httpPort);
            auth.put("session_id", generateSessionId());
            
            sendWebSocketMessage(auth.toString());
            logMessage("🔐 Authentication sent");
            
        } catch (JSONException e) {
            logError("Auth JSON error: " + e.getMessage());
        }
    }
    
    void handleWebSocketMessage(String text) {
        if (isFinishing() || isDestroyed()) return;
        
        lastMessageTime.set(System.currentTimeMillis());
        
        if (TextUtils.isEmpty(text)) {
            logError("Received empty message");
            return;
        }
        
        if (text.length() > 10 * 1024 * 1024) {
            logError("Message too large: " + text.length() + " bytes");
            return;
        }
        
        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                handleServerMessage(text);
            } catch (Exception e) {
                logError("Message handling crashed: " + e.getMessage());
            }
        });
    }
    
    void handleWebSocketClosing(int code, String reason) {
        if (isFinishing() || isDestroyed()) return;
        
        logMessage("🔌 Connection closing: " + reason + " (code: " + code + ")");
        setConnectionState(false);
        showConnectionInputs();
    }
    
    void handleWebSocketClosed(int code, String reason) {
        if (isFinishing() || isDestroyed()) return;
        
        logMessage("❌ Connection closed: " + reason + " (code: " + code + ")");
        setConnectionState(false);
        showConnectionInputs();
        cleanupFileSessions();
    }
    
    void handleWebSocketFailure(Throwable t, Response response) {
        if (isFinishing() || isDestroyed()) return;
        
        String errorMsg = "Connection failed: ";
        if (t != null) errorMsg += t.getMessage();
        else if (response != null) errorMsg += "HTTP " + response.code();
        else errorMsg += "Unknown error";
        
        logError("❌ " + errorMsg);
        setConnectionState(false);
        showProgress(false);
        showConnectionInputs();
        cleanupFileSessions();
    }

    private void handleServerMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "unknown");
            
            switch (type) {
                case "welcome":
                    handleWelcomeMessage(json);
                    break;
                case "command":
                    handleCommandMessage(json);
                    break;
                case "file_upload_chunk":
                    handleFileUploadChunk(json);
                    break;
                case "file_upload_complete":
                    handleFileUploadComplete(json);
                    break;
                case "file_download_request":
                    handleFileDownloadRequest(json);
                    break;
                case "start_screen":
                    handleStartScreen(json);
                    break;
                case "stop_screen":
                    handleStopScreen(json);
                    break;
                case "take_screenshot":
                    handleTakeScreenshot(json);
                    break;
                case "chat_message":
                    handleChatMessage(json);
                    break;
                case "ping":
                    handlePing();
                    break;
                case "call_detected":
                    handleCallDetection(json);
                    break;
                default:
                    logMessage("⚠️ Unknown message type: " + type);
            }
            
        } catch (JSONException e) {
            logError("JSON parsing error: " + e.getMessage());
        } catch (Exception e) {
            logError("Message handling error: " + e.getMessage());
        }
    }

    private void handleCallDetection(JSONObject json) {
        try {
            String callType = json.getString("call_type");
            String phoneNumber = json.optString("phone_number", "Unknown");
            String contactName = json.optString("contact_name", "Unknown");
            String timestamp = json.optString("timestamp", getCurrentTimestamp());
            
            String callMessage = "";
            switch (callType) {
                case "incoming":
                    callMessage = "📞 ইনকামিং কল: " + contactName + " (" + phoneNumber + ")";
                    break;
                case "outgoing":
                    callMessage = "📞 আউটগোয়িং কল: " + contactName + " (" + phoneNumber + ")";
                    break;
                case "missed":
                    callMessage = "📞 মিসড কল: " + contactName + " (" + phoneNumber + ")";
                    break;
                case "rejected":
                    callMessage = "📞 রিজেক্টেড কল: " + contactName + " (" + phoneNumber + ")";
                    break;
                default:
                    callMessage = "📞 কল: " + contactName + " (" + phoneNumber + ") - " + callType;
            }
            
            logMessage(callMessage);
            showCallNotification(callMessage);
            
        } catch (JSONException e) {
            logError("Call detection JSON error: " + e.getMessage());
        }
    }

    private void showCallNotification(String callMessage) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                Toast callToast = Toast.makeText(MainActivity.this, callMessage, Toast.LENGTH_LONG);
                View toastView = callToast.getView();
                if (toastView != null) {
                    toastView.setBackgroundColor(0xFF2196F3);
                    TextView textView = toastView.findViewById(android.R.id.message);
                    if (textView != null) {
                        textView.setTextColor(0xFFFFFFFF);
                        textView.setTextSize(16);
                    }
                }
                callToast.show();
                logMessage("🔔 " + callMessage);
            } catch (Exception e) {
                logError("Toast error: " + e.getMessage());
            }
        });
    }

    private void handleWelcomeMessage(JSONObject json) {
        try {
            String welcomeMsg = json.getString("message");
            logMessage("🎉 " + welcomeMsg);
            sendDeviceInfo();
        } catch (JSONException e) {
            logError("Welcome message error: " + e.getMessage());
        }
    }

    private void handleCommandMessage(JSONObject json) {
        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            
            String command = null;
            try {
                command = json.getString("command");
                if (TextUtils.isEmpty(command)) {
                    throw new JSONException("Empty command");
                }
                
                // Security: Validate command
                if (!isAllowedCommand(command)) {
                    throw new SecurityException("Command not allowed: " + command);
                }
                
                logMessage("⚡ Executing command: " + command);
                String output = executeShellCommand(command);
                
                JSONObject result = new JSONObject();
                result.put("type", "command_output");
                result.put("command", command);
                result.put("output", output);
                result.put("timestamp", getCurrentTimestamp());
                
                sendWebSocketMessage(result.toString());
                logMessage("✅ Command executed successfully");
                
            } catch (Exception e) {
                logError("Command execution error: " + e.getMessage());
                
                try {
                    JSONObject error = new JSONObject();
                    error.put("type", "command_error");
                    error.put("command", command != null ? command : "unknown");
                    error.put("error", e.getMessage());
                    error.put("timestamp", getCurrentTimestamp());
                    sendWebSocketMessage(error.toString());
                } catch (JSONException je) {
                    logError("Error sending error message: " + je.getMessage());
                }
            }
        });
    }

    private boolean isAllowedCommand(String command) {
        // Check against whitelist of safe commands
        for (String allowed : ALLOWED_COMMANDS) {
            if (command.trim().equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private String executeShellCommand(String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        BufferedReader inputReader = null;
        BufferedReader errorReader = null;
        
        try {
            // Use ProcessBuilder for better security
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command("/system/bin/sh", "-c", command);
            process = processBuilder.start();
            
            // Read output and error streams
            inputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            
            String line;
            while ((line = inputReader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            while ((line = errorReader.readLine()) != null) {
                output.append("ERROR: ").append(line).append("\n");
            }
            
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                output.append("\nProcess terminated after timeout");
            } else {
                int exitCode = process.exitValue();
                output.append("\nExit code: ").append(exitCode);
            }
            
        } catch (TimeoutException e) {
            output.append("\nCommand execution timeout");
            if (process != null) process.destroy();
        } catch (Exception e) {
            output.append("Command execution error: ").append(e.getMessage());
        } finally {
            // Close all streams properly
            closeQuietly(inputReader);
            closeQuietly(errorReader);
            if (process != null) {
                process.destroy();
            }
        }
        
        return output.toString();
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing stream", e);
            }
        }
    }

    private void handleFileUploadChunk(JSONObject json) {
        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            
            try {
                String filename = json.getString("filename");
                String remotePath = json.getString("remote_path");
                int chunkIndex = json.getInt("chunk_index");
                int totalChunks = json.getInt("total_chunks");
                String chunkData = json.getString("chunk_data");
                long totalSize = json.getLong("total_size");
                String sessionId = json.optString("session_id", generateSessionId());
                
                if (TextUtils.isEmpty(filename) || TextUtils.isEmpty(remotePath) || TextUtils.isEmpty(chunkData)) {
                    throw new IllegalArgumentException("Invalid file upload data");
                }
                
                // Validate chunk indices before processing
                if (chunkIndex < 0 || totalChunks <= 0 || chunkIndex >= totalChunks) {
                    throw new IllegalArgumentException("Invalid chunk indices: " + chunkIndex + "/" + totalChunks);
                }
                
                logMessage("📁 Receiving file: " + filename + " (" + (chunkIndex + 1) + "/" + totalChunks + ")");
                
                byte[] data = Base64.decode(chunkData, Base64.DEFAULT);
                if (data == null || data.length == 0) {
                    throw new IllegalArgumentException("Invalid chunk data");
                }
                
                boolean success = saveFileChunk(remotePath, data, chunkIndex, totalChunks, sessionId);
                
                if (success && chunkIndex == totalChunks - 1) {
                    logMessage("✅ File upload completed: " + filename);
                    
                    JSONObject complete = new JSONObject();
                    complete.put("type", "file_upload_complete");
                    complete.put("filename", filename);
                    complete.put("remote_path", remotePath);
                    complete.put("file_size", new File(remotePath).length());
                    complete.put("timestamp", getCurrentTimestamp());
                    complete.put("session_id", sessionId);
                    sendWebSocketMessage(complete.toString());
                }
                
            } catch (Exception e) {
                logError("File upload error: " + e.getMessage());
                
                try {
                    JSONObject error = new JSONObject();
                    error.put("type", "file_upload_error");
                    error.put("filename", json.optString("filename", "unknown"));
                    error.put("error", e.getMessage());
                    error.put("timestamp", getCurrentTimestamp());
                    sendWebSocketMessage(error.toString());
                } catch (JSONException je) {
                    logError("Error sending file error: " + je.getMessage());
                }
            }
        });
    }

    private boolean saveFileChunk(String filePath, byte[] data, int chunkIndex, int totalChunks, String sessionId) {
        if (!isSafeFilePath(filePath)) {
            logError("Unsafe file path: " + filePath);
            return false;
        }
        
        // Validate chunk index again
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            logError("Invalid chunk index: " + chunkIndex);
            return false;
        }
        
        Object lock = fileLocks.computeIfAbsent(sessionId, k -> new Object());
        
        synchronized (lock) {
            try {
                FileTransferSession session = fileSessions.computeIfAbsent(sessionId, 
                    k -> new FileTransferSession(filePath, totalChunks));
                
                if (!session.isValid()) {
                    logError("Invalid file session: " + sessionId);
                    return false;
                }
                
                if (session.isChunkProcessed(chunkIndex)) {
                    logMessage("Chunk " + chunkIndex + " already processed, skipping");
                    return true;
                }
                
                if (chunkIndex == 0) {
                    if (!session.initializeTempFile()) {
                        logError("Failed to initialize temp file for session: " + sessionId);
                        return false;
                    }
                }
                
                if (!session.writeChunk(data, chunkIndex)) {
                    logError("Failed to write chunk " + chunkIndex + " for session: " + sessionId);
                    return false;
                }
                
                if (session.isComplete()) {
                    if (session.finalizeFile()) {
                        logMessage("✅ File transfer completed: " + session.getFilename());
                        fileSessions.remove(sessionId);
                        fileLocks.remove(sessionId);
                        return true;
                    } else {
                        logError("Failed to finalize file for session: " + sessionId);
                        return false;
                    }
                }
                
                return true;
                
            } catch (Exception e) {
                logError("File save error for session " + sessionId + ": " + e.getMessage());
                return false;
            }
        }
    }

    private boolean isSafeFilePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        
        // Check for path traversal attacks
        if (filePath.contains("../") || filePath.contains("..\\")) {
            return false;
        }
        
        try {
            File file = new File(filePath).getCanonicalFile();
            String canonicalPath = file.getCanonicalPath();
            
            // Only allow specific safe directories
            String[] safePaths = {
                Environment.getExternalStorageDirectory().getCanonicalPath(),
                getFilesDir().getCanonicalPath(),
                getCacheDir().getCanonicalPath()
            };
            
            for (String safePath : safePaths) {
                if (canonicalPath.startsWith(safePath)) {
                    return true;
                }
            }
            
            return false;
            
        } catch (IOException e) {
            Log.e(TAG, "Canonical path check failed", e);
            return false;
        }
    }

    private void handleFileUploadComplete(JSONObject json) {
        logMessage("📁 File upload process completed");
    }

    private void handleFileDownloadRequest(JSONObject json) {
        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            
            try {
                String remotePath = json.getString("remote_path");
                String localPath = json.optString("local_path", 
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath() 
                    + File.separator + new File(remotePath).getName());
                
                if (TextUtils.isEmpty(remotePath)) {
                    sendError("Remote path cannot be empty");
                    return;
                }
                
                if (!isSafeFilePath(remotePath)) {
                    sendError("Unsafe file path: " + remotePath);
                    return;
                }
                
                logMessage("📥 Download requested: " + remotePath);
                
                File file = new File(remotePath);
                if (!file.exists()) {
                    sendError("File not found: " + remotePath);
                    return;
                }
                
                if (!file.canRead()) {
                    sendError("Cannot read file: " + remotePath);
                    return;
                }
                
                if (file.isDirectory()) {
                    sendError("Cannot download directory: " + remotePath);
                    return;
                }
                
                sendFileInChunks(file, localPath);
                
            } catch (Exception e) {
                logError("File download error: " + e.getMessage());
                sendError("Download failed: " + e.getMessage());
            }
        });
    }

    private void sendFileInChunks(File file, String localPath) {
        String sessionId = generateSessionId();
        
        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                long fileSize = file.length();
                final int CHUNK_SIZE = 64 * 1024;
                final int TOTAL_CHUNKS = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
                
                logMessage("📤 Starting file upload: " + file.getName() + " (" + TOTAL_CHUNKS + " chunks)");
                
                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;
                int chunkIndex = 0;
                
                while ((bytesRead = fis.read(buffer)) != -1 && chunkIndex < 1000) {
                    if (isFinishing() || isDestroyed()) break;
                    
                    final int currentChunkIndex = chunkIndex;
                    final byte[] chunkData = Arrays.copyOf(buffer, bytesRead);
                    
                    boolean sent = sendFileChunkWithRetry(file, localPath, chunkData, currentChunkIndex, 
                                                         TOTAL_CHUNKS, fileSize, sessionId);
                    
                    if (!sent) {
                        logError("Failed to send chunk " + currentChunkIndex + ", aborting transfer");
                        return;
                    }
                    
                    chunkIndex++;
                    // Small delay to prevent overwhelming the network
                    Thread.sleep(10);
                }
                
                if (!isFinishing() && !isDestroyed()) {
                    sendFileCompletion(file, localPath, fileSize, TOTAL_CHUNKS, sessionId);
                    logMessage("✅ File download completed: " + file.getName() + " (" + fileSize + " bytes)");
                }
                
            } catch (Exception e) {
                logError("File send error: " + e.getMessage());
            } finally {
                closeQuietly(fis);
            }
        });
    }

    private boolean sendFileChunkWithRetry(File file, String localPath, byte[] chunkData, 
                                           int chunkIndex, int totalChunks, long fileSize, String sessionId) {
        final int MAX_RETRIES = 3;
        int retryCount = 0;
        
        while (retryCount < MAX_RETRIES) {
            if (isFinishing() || isDestroyed()) return false;
            
            try {
                String encodedChunk = Base64.encodeToString(chunkData, Base64.DEFAULT);
                
                JSONObject chunk = new JSONObject();
                chunk.put("type", "file_download_chunk");
                chunk.put("filename", file.getName());
                chunk.put("local_path", localPath);
                chunk.put("chunk_index", chunkIndex);
                chunk.put("total_chunks", totalChunks);
                chunk.put("chunk_data", encodedChunk);
                chunk.put("chunk_size", chunkData.length);
                chunk.put("total_size", fileSize);
                chunk.put("timestamp", getCurrentTimestamp());
                chunk.put("session_id", sessionId);
                
                if (sendWebSocketMessage(chunk.toString())) {
                    if (chunkIndex % 10 == 0 || chunkIndex == totalChunks - 1) {
                        int progress = Math.min(100, (int) (((chunkIndex + 1) * 100) / totalChunks));
                        logMessage("📤 Uploading: " + progress + "% (" + (chunkIndex + 1) + "/" + totalChunks + ")");
                        updateProgressBar(progress);
                    }
                    return true;
                }
                
            } catch (Exception e) {
                logError("Chunk " + chunkIndex + " send error (attempt " + (retryCount + 1) + "): " + e.getMessage());
            }
            
            retryCount++;
            if (retryCount < MAX_RETRIES) {
                try {
                    Thread.sleep(1000 * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return false;
    }

    private void sendFileCompletion(File file, String localPath, long fileSize, int totalChunks, String sessionId) {
        try {
            JSONObject complete = new JSONObject();
            complete.put("type", "file_download_complete");
            complete.put("filename", file.getName());
            complete.put("local_path", localPath);
            complete.put("file_size", fileSize);
            complete.put("total_chunks", totalChunks);
            complete.put("timestamp", getCurrentTimestamp());
            complete.put("session_id", sessionId);
            
            sendWebSocketMessage(complete.toString());
        } catch (Exception e) {
            logError("File completion error: " + e.getMessage());
        }
    }

    private void handleStartScreen(JSONObject json) {
        logMessage("📺 Screen sharing requested");
        sendError("Screen sharing requires additional permissions (not implemented)");
    }

    private void handleStopScreen(JSONObject json) {
        logMessage("📺 Screen sharing stopped");
    }

    private void handleTakeScreenshot(JSONObject json) {
        logMessage("📸 Screenshot requested");
        sendError("Screenshot functionality requires additional setup");
    }

    private void handleChatMessage(JSONObject json) {
        try {
            String message = json.getString("message");
            String sender = json.optString("sender", "Server");
            
            if (!TextUtils.isEmpty(message)) {
                logMessage("💬 " + sender + ": " + message);
            }
            
        } catch (JSONException e) {
            logError("Chat message error: " + e.getMessage());
        }
    }

    private void handlePing() {
        try {
            JSONObject pong = new JSONObject();
            pong.put("type", "pong");
            pong.put("timestamp", getCurrentTimestamp());
            pong.put("device_id", getDeviceId());
            
            sendWebSocketMessage(pong.toString());
        } catch (JSONException e) {
            logError("Pong error: " + e.getMessage());
        }
    }

    private void sendDeviceInfo() {
        executor.execute(() -> {
            if (isFinishing() || isDestroyed()) return;
            
            try {
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("type", "device_info");
                deviceInfo.put("device_id", getDeviceId());
                deviceInfo.put("model", android.os.Build.MODEL);
                deviceInfo.put("brand", android.os.Build.BRAND);
                deviceInfo.put("android_version", android.os.Build.VERSION.RELEASE);
                deviceInfo.put("sdk_version", android.os.Build.VERSION.SDK_INT);
                deviceInfo.put("manufacturer", android.os.Build.MANUFACTURER);
                deviceInfo.put("websocket_port", webSocketPort);
                deviceInfo.put("http_port", httpPort);
                deviceInfo.put("timestamp", getCurrentTimestamp());
                
                sendWebSocketMessage(deviceInfo.toString());
                logMessage("📱 Device info sent");
                
            } catch (JSONException e) {
                logError("Device info error: " + e.getMessage());
            }
        });
    }

    private boolean sendWebSocketMessage(String message) {
        synchronized (connectionLock) {
            if (webSocket == null || !isConnected || isFinishing() || isDestroyed()) {
                logError("Cannot send message - not connected or activity finishing");
                return false;
            }
            
            try {
                webSocket.send(message);
                Log.d(TAG, "📤 Sent: " + (message.length() > 50 ? message.substring(0, 50) + "..." : message));
                return true;
            } catch (Exception e) {
                logError("Send message error: " + e.getMessage());
                setConnectionState(false);
                return false;
            }
        }
    }

    private void sendError(String errorMessage) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", "error");
            error.put("message", errorMessage);
            error.put("timestamp", getCurrentTimestamp());
            
            sendWebSocketMessage(error.toString());
        } catch (JSONException e) {
            logError("Error message error: " + e.getMessage());
        }
    }

    private void sendPing() {
        try {
            JSONObject ping = new JSONObject();
            ping.put("type", "ping");
            ping.put("timestamp", getCurrentTimestamp());
            sendWebSocketMessage(ping.toString());
        } catch (JSONException e) {
            logError("Ping error: " + e.getMessage());
        }
    }

    private void disconnectFromServer() {
        synchronized (connectionLock) {
            logMessage("🔌 Disconnecting from server...");
            
            isConnected = false;
            lastMessageTime.set(0);
            
            if (webSocket != null) {
                try {
                    webSocket.close(1000, "Client disconnected");
                } catch (Exception e) {
                    Log.e(TAG, "Error closing WebSocket", e);
                }
                webSocket = null;
            }
            
            webSocketListener = null;
            cleanupFileSessions();
            
            setConnectionState(false);
            showConnectionInputs();
            logMessage("❌ Disconnected from server");
        }
    }

    private void cleanupFileSessions() {
        for (FileTransferSession session : fileSessions.values()) {
            try {
                session.cleanup();
            } catch (Exception e) {
                Log.e(TAG, "Error cleaning up file session", e);
            }
        }
        fileSessions.clear();
        fileLocks.clear();
    }

    private void setConnectionState(boolean connected) {
        synchronized (connectionLock) {
            isConnected = connected;
        }
        
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                if (connected) {
                    tvStatus.setText("🟢 Connected to " + serverIp + ":" + webSocketPort);
                    tvStatus.setTextColor(0xFF4CAF50);
                    llConnected.setVisibility(View.VISIBLE);
                    llDisconnected.setVisibility(View.GONE);
                    hideConnectionInputs();
                } else {
                    tvStatus.setText("🔴 Disconnected");
                    tvStatus.setTextColor(0xFFFF0000);
                    llConnected.setVisibility(View.GONE);
                    llDisconnected.setVisibility(View.VISIBLE);
                    showProgress(false);
                    showConnectionInputs();
                }
            } catch (Exception e) {
                Log.e(TAG, "UI update error: " + e.getMessage());
            }
        });
    }

    private void showProgress(boolean show) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
                btnConnect.setEnabled(!show);
                if (show) {
                    progressBar.setIndeterminate(true);
                } else {
                    progressBar.setProgress(0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Progress show error: " + e.getMessage());
            }
        });
    }

    private void updateProgressBar(int progress) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                if (progressBar.getVisibility() == View.VISIBLE) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(Math.min(progress, 100));
                }
            } catch (Exception e) {
                Log.e(TAG, "Progress update error: " + e.getMessage());
            }
        });
    }

    private void startConnectionHealthCheck() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected && !isFinishing() && !isDestroyed()) {
                    long timeSinceLastMessage = System.currentTimeMillis() - lastMessageTime.get();
                    if (timeSinceLastMessage > 60000) {
                        logMessage("🫀 Connection health check: No message for " + timeSinceLastMessage + "ms");
                        sendPing();
                    }
                    
                    if (isConnected && !isFinishing() && !isDestroyed()) {
                        mainHandler.postDelayed(this, 30000);
                    }
                }
            }
        }, 30000);
    }

    private void logMessage(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";
        
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                tvLog.append(logEntry);
                svLog.postDelayed(() -> {
                    try {
                        if (!isFinishing() && !isDestroyed()) {
                            svLog.fullScroll(View.FOCUS_DOWN);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Scroll error: " + e.getMessage());
                    }
                }, 100);
            } catch (Exception e) {
                Log.e(TAG, "Log update error: " + e.getMessage());
            }
        });
        
        Log.i(TAG, message);
    }

    private void logError(String error) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = "[" + timestamp + "] ❌ " + error + "\n";
        
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                tvLog.append(logEntry);
                svLog.postDelayed(() -> {
                    try {
                        if (!isFinishing() && !isDestroyed()) {
                            svLog.fullScroll(View.FOCUS_DOWN);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Scroll error: " + e.getMessage());
                    }
                }, 100);
            } catch (Exception e) {
                Log.e(TAG, "Error log update error: " + e.getMessage());
            }
        });
        
        Log.e(TAG, error);
    }

    private void showToast(String message) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Toast error: " + e.getMessage());
            }
        });
    }

    private String generateSessionId() {
        return String.valueOf(random.nextLong()) + "_" + System.currentTimeMillis();
    }

    private String getDeviceId() {
        try {
            String androidId = android.provider.Settings.Secure.getString(
                getContentResolver(), 
                android.provider.Settings.Secure.ANDROID_ID
            );
            return androidId != null ? androidId : "unknown_" + System.currentTimeMillis();
        } catch (Exception e) {
            return "error_" + System.currentTimeMillis();
        }
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private boolean isValidIpAddress(String ip) {
        return IP_PATTERN.matcher(ip).matches() || ip.equals("localhost") || ip.equals("10.0.2.2");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (executor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) executor).setCorePoolSize(1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (executor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) executor).setCorePoolSize(2);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        Log.d(TAG, "Activity onDestroy called");
        
        disconnectFromServer();
        
        if (executor != null) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (okHttpClient != null) {
            try {
                okHttpClient.dispatcher().executorService().shutdown();
                okHttpClient.connectionPool().evictAll();
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down OkHttpClient", e);
            }
        }
        
        cleanupFileSessions();
        
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        
        // Clear all references to prevent leaks
        webSocketListener = null;
        webSocket = null;
        
        Log.d(TAG, "Activity cleanup completed");
    }

    private static class FileTransferSession {
        private final String filePath;
        private final String tempFilePath;
        private final int totalChunks;
        private final Set<Integer> receivedChunks;
        private RandomAccessFile tempFile;
        private boolean initialized = false;
        
        FileTransferSession(String filePath, int totalChunks) {
            this.filePath = filePath;
            this.tempFilePath = filePath + ".tmp_" + System.currentTimeMillis();
            this.totalChunks = totalChunks;
            this.receivedChunks = ConcurrentHashMap.newKeySet();
        }
        
        boolean isValid() {
            return totalChunks > 0 && filePath != null && !filePath.isEmpty();
        }
        
        boolean initializeTempFile() {
            try {
                File tempFile = new File(tempFilePath);
                File parent = tempFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        return false;
                    }
                }
                
                this.tempFile = new RandomAccessFile(tempFile, "rw");
                this.tempFile.setLength(0);
                initialized = true;
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Temp file initialization failed", e);
                return false;
            }
        }
        
        boolean writeChunk(byte[] data, int chunkIndex) {
            if (!initialized || tempFile == null) {
                return false;
            }
            
            try {
                // Calculate position for this chunk to support out-of-order delivery
                long position = (long) chunkIndex * data.length;
                tempFile.seek(position);
                tempFile.write(data);
                receivedChunks.add(chunkIndex);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Chunk write failed", e);
                return false;
            }
        }
        
        boolean isChunkProcessed(int chunkIndex) {
            return receivedChunks.contains(chunkIndex);
        }
        
        boolean isComplete() {
            return receivedChunks.size() >= totalChunks;
        }
        
        boolean finalizeFile() {
            try {
                if (tempFile != null) {
                    tempFile.close();
                    tempFile = null;
                }
                
                File tempFile = new File(tempFilePath);
                File finalFile = new File(filePath);
                
                // Ensure parent directory exists
                File parent = finalFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        return false;
                    }
                }
                
                if (tempFile.exists()) {
                    return tempFile.renameTo(finalFile);
                }
                return false;
            } catch (Exception e) {
                Log.e(TAG, "File finalization failed", e);
                return false;
            }
        }
        
        void cleanup() {
            try {
                if (tempFile != null) {
                    tempFile.close();
                    tempFile = null;
                }
                new File(tempFilePath).delete();
            } catch (Exception e) {
                Log.e(TAG, "Session cleanup failed", e);
            }
        }
        
        String getFilename() {
            return new File(filePath).getName();
        }
    }
}