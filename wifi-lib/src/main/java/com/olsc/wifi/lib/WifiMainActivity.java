package com.olsc.wifi.lib;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WifiMainActivity extends Activity {

    private TextView tvStatus, tvIpLink;
    private ImageView ivQrCode;
    private View cardConnection;
    private Button btnAction;
    private WifiManager wifiManager;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ServerSocket serverSocket;
    private boolean isServerRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isStartingHotspot = false;
    private Runnable ipPollRunnable;
    private static final int IP_POLL_INTERVAL = 1000;
    private static final int PERMISSIONS_REQUEST_CODE = 1001;
    private static final int WIFI_PANEL_REQUEST_CODE = 1002;
    private List<ScanResult> cachedScanResults = new ArrayList<>();
    private boolean isScanning = false;
    private boolean isCheckingConnection = false;

    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            isScanning = false;
            updateScanResults();
            
            if (!isWifiConnected() && hotspotReservation == null && !isStartingHotspot) {
                mainHandler.post(WifiMainActivity.this::startHotspotAndServer);
            }
        }
    };

    private final BroadcastReceiver networkChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isWifiConnected() && isCheckingConnection) {
                onWifiSuccess();
            }
        }
    };

    private void updateScanResults() {
        @SuppressLint("MissingPermission")
        List<ScanResult> results = wifiManager.getScanResults();
        if (results != null) {
            cachedScanResults = new ArrayList<>(results);
            // Hide network names from UI, only show count.
            final String statusText = getString(R.string.wifi_found_networks, results.size());
            mainHandler.post(() -> {
                tvStatus.setText(statusText);
                Log.d("WIFI_SCAN", "Scan finished, found " + results.size() + " networks. (Names hidden from UI)");
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(wifiScanReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(networkChangeReceiver);
        } catch (Exception ignored) {}
        
        if (hotspotReservation != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hotspotReservation.close();
            }
            hotspotReservation = null;
        }
        stopHttpServer();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_main);

        tvStatus = findViewById(R.id.tv_status);
        tvIpLink = findViewById(R.id.tv_ip_link);
        ivQrCode = findViewById(R.id.iv_qrcode);
        cardConnection = findViewById(R.id.card_connection);
        btnAction = findViewById(R.id.btn_action);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        IntentFilter scanFilter = new IntentFilter();
        scanFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, scanFilter);

        IntentFilter networkFilter = new IntentFilter();
        networkFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkChangeReceiver, networkFilter);

        btnAction.setOnClickListener(v -> checkPermissionsAndStart());

        mainHandler.postDelayed(this::checkPermissionsAndStart, 800);
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissions = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                permissions.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            if (!permissions.isEmpty()) {
                requestPermissions(permissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
                return;
            }
        }
        startProcess();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startProcess();
            } else {
                Toast.makeText(this, "Permissions denied. Cannot proceed.", Toast.LENGTH_SHORT).show();
                finish(); // Cannot proceed without permissions
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void startProcess() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "Please enable Location (GPS) first!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        if (!wifiManager.isWifiEnabled()) {
            tvStatus.setText("Enabling Wi-Fi...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                startActivityForResult(panelIntent, WIFI_PANEL_REQUEST_CODE);
            } else {
                wifiManager.setWifiEnabled(true);
            }
            return;
        }

        if (isWifiConnected()) {
            tvStatus.setText("Already connected to Wi-Fi.");
            onWifiSuccess();
        } else {
            tvStatus.setText("Scanning for nearby Wi-Fi...");
            isCheckingConnection = true;
            startWifiScan();
            
            mainHandler.postDelayed(() -> {
                if (hotspotReservation == null && !isStartingHotspot && !isWifiConnected()) {
                    Log.w("WIFI_SCAN", "Scan timeout, starting hotspot");
                    startHotspotAndServer();
                }
            }, 5000);
        }
    }

    @SuppressLint("SetTextI18n")
    private void onWifiSuccess() {
        tvStatus.setText("WIFI Connected!");
        mainHandler.postDelayed(() -> {
            WifiConnectManager.notifySuccess();
            finish();
        }, 1000);
    }

    private void startWifiScan() {
        if (isScanning) return;
        isScanning = true;
        @SuppressLint("MissingPermission")
        boolean success = wifiManager.startScan();
        if (!success) {
            isScanning = false;
            Log.e("WIFI_SCAN", "startScan() failed");
            startHotspotAndServer();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WIFI_PANEL_REQUEST_CODE) {
            new Handler(Looper.getMainLooper()).postDelayed(this::startProcess, 1000);
        }
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        } else {
            try {
                int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
                return mode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                android.net.NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                return info != null && info.isConnected();
            }
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void startHotspotAndServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isStartingHotspot || hotspotReservation != null) {
                return;
            }
            
            isStartingHotspot = true;
            try {
                wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                    @SuppressLint("SetTextI18n")
                    @Override
                    public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                        super.onStarted(reservation);
                        isStartingHotspot = false;
                        hotspotReservation = reservation;
                        
                        String ssid = "";
                        String password = "";

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            SoftApConfiguration config = reservation.getSoftApConfiguration();
                            ssid = config.getSsid();
                            password = config.getPassphrase();
                        } else {
                            @SuppressWarnings("deprecation")
                            WifiConfiguration config = reservation.getWifiConfiguration();
                            if (config != null) {
                                ssid = config.SSID;
                                password = config.preSharedKey;
                            }
                        }

                        String wifiQrContent = "WIFI:S:" + ssid + ";T:WPA;P:" + password + ";;";
                        Bitmap qrBitmap = generateQrCode(wifiQrContent);
                        if (qrBitmap != null) {
                            ivQrCode.setImageBitmap(qrBitmap);
                        }
                        
                        cardConnection.setVisibility(View.VISIBLE);
                        tvStatus.setText("Hotspot active: " + ssid + "\nPlease connect other device to assist.");
                        startHttpServer();
                        
                        startIpAndClientPolling(ssid, password);
                    }

                    @SuppressLint("SetTextI18n")
                    @Override
                    public void onStopped() {
                        super.onStopped();
                        isStartingHotspot = false;
                        mainHandler.post(() -> {
                            tvStatus.setText("Hotspot Stopped.");
                            cardConnection.setVisibility(View.GONE);
                            stopPolling();
                        });
                        stopHttpServer();
                    }

                    @Override
                    public void onFailed(int reason) {
                        super.onFailed(reason);
                        isStartingHotspot = false;
                        mainHandler.post(() -> {
                            tvStatus.setText("Hotspot Failed: " + reason);
                            btnAction.setVisibility(View.VISIBLE);
                        });
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (IllegalStateException e) {
                isStartingHotspot = false;
                Log.e("WIFI_HOTSPOT", "Active request exists", e);
            }
        } else {
            Toast.makeText(this, "Hotspot requires Android 8.0+", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void startHttpServer() {
        if (isServerRunning) return;
        isServerRunning = true;

        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8765, 50, InetAddress.getByName("0.0.0.0"));
                while (isServerRunning) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                if (isServerRunning) e.printStackTrace();
            }
        });
        serverThread.start();
    }

    private void startIpAndClientPolling(String ssid, String password) {
        stopPolling();
        ipPollRunnable = new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                String ip = getHotspotIpAddress();
                if (!ip.equals("127.0.0.1") && !ip.equals("192.168.43.1") || Build.VERSION.SDK_INT < 26) {
                    tvIpLink.setText("HTTP Link: http://" + ip + ":8765");
                }
                
                
                mainHandler.postDelayed(this, IP_POLL_INTERVAL);
            }
        };
        mainHandler.post(ipPollRunnable);
    }

    private void stopPolling() {
        if (ipPollRunnable != null) {
            mainHandler.removeCallbacks(ipPollRunnable);
            ipPollRunnable = null;
        }
    }


    private Bitmap generateQrCode(String content) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            return barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 400, 400);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getHotspotIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getName();
                if (name.contains("wlan") || name.contains("ap") || name.contains("swlan") || name.contains("hotspot")) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    private void stopHttpServer() {
        isServerRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint({"MissingPermission", "SetTextI18n"})
    private void handleClient(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            Log.d("WIFI_HTTP", "Request: " + requestLine);
            
            String path = "/";
            String method = "GET";
            if (requestLine.contains(" ")) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    method = parts[0].toUpperCase();
                    path = parts[1];
                }
            }

            int contentLength = 0;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                }
            }
            
            String css = "body { font-family: system-ui, -apple-system, sans-serif; padding: 20px; background-color: #f5f5f7; color: #1d1d1f; } " +
                         ".container { max-width: 400px; margin: 0 auto; background: white; padding: 24px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); } " +
                         "h2 { margin-top: 0; color: #000; font-size: 24px; } " +
                         "label { font-size: 14px; font-weight: 500; color: #86868b; display: block; margin-bottom: 4px; } " +
                         "input, select { margin-bottom: 16px; padding: 12px; font-size: 16px; width: 100%; box-sizing: border-box; border: 1px solid #d2d2d7; border-radius: 8px; background-color: #fff; } " +
                         "button { background-color: #0071e3; color: white; border: none; cursor: pointer; padding: 14px; border-radius: 8px; font-size: 16px; font-weight: 600; width: 100%; transition: background-color 0.2s; } " +
                         "button:hover { background-color: #0077ed; }";

            if (path.equals("/") || path.startsWith("/?")) {
                List<ScanResult> results = cachedScanResults;
                Set<String> ssids = new HashSet<>();
                if (results != null) {
                    for (ScanResult sr : results) {
                        if (sr.SSID != null && !sr.SSID.isEmpty()) {
                            ssids.add(sr.SSID);
                        }
                    }
                }
                
                StringBuilder options = new StringBuilder();
                if (ssids.isEmpty()) {
                    options.append("<option value=\"\">").append(getString(R.string.wifi_web_no_networks)).append("</option>");
                } else {
                    for (String s : ssids) {
                        options.append("<option value=\"").append(s).append("\">").append(s).append("</option>\n");
                    }
                }
                
                String js = "<script>" +
                            "function updateSsid(val) { document.getElementsByName('manual_ssid')[0].value = val; }" +
                            "</script>";

                String htmlBody = "<div class=\"container\">" +
                        "<h2>" + getString(R.string.wifi_web_title) + "</h2>" +
                        "<form method=\"POST\" action=\"/connect\">" +
                        "<label>" + getString(R.string.wifi_web_select_label) + "</label>" +
                        "<select name=\"ssid\" onchange=\"updateSsid(this.value)\"><option value=\"\">" + getString(R.string.wifi_web_select_placeholder) + "</option>" + options.toString() + "</select>" +
                        "<label>" + getString(R.string.wifi_web_manual_label) + "</label>" +
                        "<input type=\"text\" name=\"manual_ssid\" placeholder=\"" + getString(R.string.wifi_web_ssid_placeholder) + "\">" +
                        "<label>" + getString(R.string.wifi_web_password_label) + "</label>" +
                        "<input type=\"password\" name=\"password\" placeholder=\"" + getString(R.string.wifi_web_password_placeholder) + "\">" +
                        "<button type=\"submit\">" + getString(R.string.wifi_web_connect_btn) + "</button></form></div>" +
                        js;

                String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" +
                        "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>" + css + "</style></head><body>" +
                        htmlBody + "</body></html>";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
            } else if (path.startsWith("/connect")) {
                String body = "";
                if (method.equals("POST") && contentLength > 0) {
                    char[] bodyChars = new char[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = reader.read(bodyChars, totalRead, contentLength - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    body = new String(bodyChars);
                } else if (method.equals("GET") && path.contains("?")) {
                    body = path.substring(path.indexOf("?") + 1);
                }

                String ssid = "";
                String manualSsid = "";
                String password = "";
                
                if (!body.isEmpty()) {
                    String[] params = body.split("&");
                    for (String param : params) {
                        String[] kv = param.split("=");
                        if (kv.length == 2) {
                            try {
                                String key = URLDecoder.decode(kv[0], "UTF-8");
                                String value = URLDecoder.decode(kv[1], "UTF-8");
                                if (key.equals("ssid")) ssid = value;
                                else if (key.equals("manual_ssid")) manualSsid = value;
                                else if (key.equals("password")) password = value;
                            } catch (Exception ignored) {}
                        }
                    }
                }
                
                String finalSsid = (manualSsid != null && !manualSsid.trim().isEmpty()) ? manualSsid.trim() : ssid;
                
                if (finalSsid.isEmpty() && method.equals("GET")) {
                    String redirectHome = "HTTP/1.1 302 Found\r\nLocation: /\r\n\r\n";
                    out.write(redirectHome.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                }

                String htmlResponse = "<!DOCTYPE html><html><head>" +
                        "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                        "<meta http-equiv=\"refresh\" content=\"5;url=/\">" +
                        "<style>" + css + "</style></head><body>" +
                        "<div class=\"container\">" +
                        "<h2>" + getString(R.string.wifi_web_received_title) + "</h2>" +
                        "<p>" + getString(R.string.wifi_web_attempting_msg, finalSsid) + "</p>" +
                        "<div style=\"margin-top:24px; padding:12px; background:#f0f7ff; border-radius:8px; color:#0052cc; font-size:14px; text-align:center;\">" +
                        "Auto-redirect in 5 seconds...<br>5 秒后自动跳转..." +
                        "</div>" +
                        "</div></body></html>";

                String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: " + htmlResponse.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                        "Connection: close\r\n\r\n";
                
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(htmlResponse.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
                String finalConfigSsid = finalSsid;
                String finalConfigPass = password;
                // Delay the connection attempt to allow the HTTP response to be delivered fully
                mainHandler.postDelayed(() -> {
                    showSuccessDialog(finalConfigSsid);
                    connectToWifi(finalConfigSsid, finalConfigPass);
                }, 1000); // 1 second delay
            } else {
                String response = "HTTP/1.1 404 Not Found\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            Log.e("WIFI_HTTP", "Client error", e);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }
    
    private void showSuccessDialog(String ssid) {
        View view = getLayoutInflater().inflate(R.layout.dialog_success, null);
        TextView tvTitle = view.findViewById(R.id.tv_dialog_title);
        TextView tvMsg = view.findViewById(R.id.tv_dialog_msg);
        tvTitle.setText(R.string.wifi_dialog_title);
        tvMsg.setText(getString(R.string.wifi_dialog_subtitle, ssid));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        dialog.show();

        mainHandler.postDelayed(() -> {
            if (dialog.isShowing()) dialog.dismiss();
        }, 4000);
    }

    @SuppressLint("SetTextI18n")
    private void connectToWifi(String ssid, String password) {
        // First, definitely close the hotspot and server
        if (hotspotReservation != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hotspotReservation.close();
            }
            hotspotReservation = null;
        }
        stopHttpServer();
        
        Log.d("WIFI_CONNECT", "Attempting connection to: " + ssid);
        tvStatus.setText(getString(R.string.wifi_connecting_to, ssid) + "...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 1. Add suggestion (background-ish connection)
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .setIsAppInteractionRequired(true)
                    .build();
            List<WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(suggestion);
            wifiManager.addNetworkSuggestions(list);
            
            // 2. Also try NetworkSpecifier for a more immediate/guided connection
            try {
                android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(new android.net.wifi.WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)
                        .setWpa2Passphrase(password)
                        .build())
                    .build();

                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(@NonNull android.net.Network network) {
                            super.onAvailable(network);
                            cm.bindProcessToNetwork(network); 
                            mainHandler.post(() -> {
                                tvStatus.setText(R.string.wifi_status_connected);
                                onWifiSuccess();
                            });
                        }
                        
                        @Override
                        public void onUnavailable() {
                            super.onUnavailable();
                            Log.e("WIFI_CONNECT", "Network Unavailable");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("WIFI_CONNECT", "Specifier failed", e);
            }
            
            tvStatus.setText(getString(R.string.wifi_wait_system_dialog));
        } else {
            @SuppressWarnings("deprecation")
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = String.format("\"%s\"", ssid);
            wifiConfig.preSharedKey = String.format("\"%s\"", password);
            
            @SuppressWarnings("deprecation")
            int netId = wifiManager.addNetwork(wifiConfig);
            @SuppressWarnings("deprecation")
            boolean d = wifiManager.disconnect();
            @SuppressWarnings("deprecation")
            boolean e = wifiManager.enableNetwork(netId, true);
            @SuppressWarnings("deprecation")
            boolean r = wifiManager.reconnect();
            
            tvStatus.setText(getString(R.string.wifi_request_connection, ssid));
        }
    }
}
