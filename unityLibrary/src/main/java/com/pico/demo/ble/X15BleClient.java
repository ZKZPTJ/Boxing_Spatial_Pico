package com.pico.demo.ble;

import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

/** Native callbacks must extend ScanCallback/BluetoothGattCallback, not Java interfaces. */
public final class X15BleClient {
    public interface Listener { void onEvent(String json); }
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static X15BleClient current;
    private final Activity activity;
    private final Listener listener;
    private final int session, scanTimeout, connectTimeout;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private boolean active = true, scanning, connecting, connected;
    private String name = "", address = "";
    private final Runnable scanExpired = () -> fail("扫描超时：未发现可连接的 X15 设备。");
    private final Runnable connectExpired = () -> fail("X15 设备连接超时。");

    private X15BleClient(Activity activity, Listener listener, int session, int scanTimeout, int connectTimeout) {
        this.activity = activity;
        this.listener = listener;
        this.session = session;
        this.scanTimeout = scanTimeout;
        this.connectTimeout = connectTimeout;
    }

    public static void start(Activity activity, Listener listener, int session, int scanTimeout, int connectTimeout) {
        main.post(() -> {
            if (current != null) current.close();
            current = new X15BleClient(activity, listener, session, scanTimeout, connectTimeout);
            current.begin();
        });
    }

    public static void stop() {
        main.post(() -> {
            if (current != null) current.close();
            current = null;
        });
    }

    private void begin() {
        try {
            BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) { fail("当前设备不支持蓝牙。"); return; }
            if (!adapter.isEnabled()) { fail("蓝牙未开启，请在系统设置中开启蓝牙。"); return; }
            if (Build.VERSION.SDK_INT < 31) {
                LocationManager location = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
                if (location == null || !location.isLocationEnabled()) {
                    fail("系统定位未开启，Android 11 及以下扫描 BLE 需要开启定位。"); return;
                }
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { fail("BLE 扫描器不可用。"); return; }
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    main.post(() -> found(result));
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult result : results) main.post(() -> found(result));
                }
                @Override public void onScanFailed(int code) {
                    main.post(() -> { if (scanning) fail("BLE 扫描失败，错误码：" + code); });
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanning = true;
            scanner.startScan(null, settings, scanCallback);
            main.postDelayed(scanExpired, scanTimeout);
        } catch (Exception e) { fail("无法开始 BLE 扫描：" + e.getMessage()); }
    }

    private void found(ScanResult result) {
        if (!active || !scanning || result == null) return;
        try {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            if (!address.isEmpty()) {
                if (connected && address.equalsIgnoreCase(device.getAddress()) && record != null)
                    send("advertisement", "", record.getBytes());
                return;
            }
            String candidate = record == null ? null : record.getDeviceName();
            if (candidate == null || candidate.isEmpty()) candidate = device.getName();
            if (candidate == null || !candidate.toUpperCase(Locale.ROOT).startsWith("X15")) return;
            if (Build.VERSION.SDK_INT >= 26 && !result.isConnectable()) return;
            name = candidate;
            address = device.getAddress();
            // X15AdvDecoder consumes advertisements, not arbitrary GATT characteristic values.
            // Keep scanning this address after the GATT connection is established.
            main.removeCallbacks(scanExpired);
            connecting = true;
            send("connecting", "", record == null ? null : record.getBytes());
            main.postDelayed(connectExpired, connectTimeout);
            gatt = device.connectGatt(activity.getApplicationContext(), false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
                    main.post(() -> connectionChanged(source, status, state));
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) fail("无法创建 X15 GATT 连接。");
        } catch (Exception e) { fail("连接 X15 失败：" + e.getMessage()); }
    }

    private void connectionChanged(BluetoothGatt source, int status, int state) {
        if (!active || source != gatt) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("X15 GATT 连接失败，错误码：" + status); return;
        }
        if (state == BluetoothProfile.STATE_CONNECTED && connecting) {
            connecting = false;
            connected = true;
            main.removeCallbacks(connectExpired);
            send("connected", "", null);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            fail(connected ? "X15 设备已断开连接。" : "X15 设备拒绝连接或已断开。");
        }
    }

    private void send(String state, String error, byte[] advertisement) {
        try {
            JSONObject message = new JSONObject();
            message.put("session", session);
            message.put("state", state);
            message.put("name", name);
            message.put("address", address);
            message.put("error", error);
            if (advertisement != null)
                message.put("advertisement", Base64.encodeToString(advertisement, Base64.NO_WRAP));
            listener.onEvent(message.toString());
        } catch (Exception ignored) { /* Managed side can have been destroyed during shutdown. */ }
    }

    private void fail(String error) {
        if (!active) return;
        send("error", error, null);
        close();
    }

    private void stopScan() {
        main.removeCallbacks(scanExpired);
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); }
        catch (Exception ignored) { }
    }

    private void close() {
        active = false;
        connecting = false;
        connected = false;
        stopScan();
        main.removeCallbacks(connectExpired);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }
}
