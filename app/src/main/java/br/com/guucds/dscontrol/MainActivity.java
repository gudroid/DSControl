package br.com.guucds.dscontrol;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * DS Control: interface web (assets/index.html) + Bluetooth nativo.
 * O canal 0xDEAF do DS Player não tem descritor de aviso (CCCD), por isso o Chrome
 * não recebe as respostas. Aqui o Android liga os avisos direto e repassa para a página.
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    static final UUID SVC = UUID.fromString("0000deaf-0000-1000-8000-00805f9b34fb");
    static final UUID CHR = UUID.fromString("0000deaf-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());
    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic chr;
    final LinkedList<byte[]> queue = new LinkedList<>();
    boolean writing = false;
    boolean userClosed = false;
    BluetoothDevice lastDevice;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFF0B0B0F);
        web.addJavascriptInterface(new Bridge(), "AndroidBLE");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        askPermissions();
    }

    // ---------- permissões ----------
    String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    boolean hasPerms() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : neededPerms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23 && !hasPerms()) requestPermissions(neededPerms(), 1);
    }

    // ---------- ponte com a página ----------
    void js(final String fn, final String arg) {
        final String a = arg == null ? "" : arg.replace("\\", "\\\\").replace("'", "\\'");
        ui.post(() -> web.evaluateJavascript("window.__ble&&window.__ble." + fn + "('" + a + "')", null));
    }
    void log(String m) { js("onLog", m); }

    class Bridge {
        @JavascriptInterface public boolean available() { return true; }
        @JavascriptInterface public void connect() { ui.post(MainActivity.this::startScan); }
        @JavascriptInterface public void disconnect() {
            ui.post(() -> { userClosed = true; if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; } js("onDisconnected", ""); });
        }
        @JavascriptInterface public void write(String hex) {
            byte[] data = fromHex(hex);
            synchronized (queue) { queue.add(data); }
            ui.post(MainActivity.this::pump);
        }
    }

    // ---------- busca de aparelhos ----------
    void startScan() {
        if (adapter == null) { js("onError", "Este celular não tem Bluetooth"); return; }
        if (!hasPerms()) { askPermissions(); js("onError", "Permita o acesso ao Bluetooth e toque em Conectar de novo"); return; }
        if (!adapter.isEnabled()) { js("onError", "Ligue o Bluetooth do celular"); return; }
        final List<BluetoothDevice> found = new ArrayList<>();
        final ArrayAdapter<String> names = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        final BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String n = d.getName();
                if (r.getScanRecord() != null && r.getScanRecord().getDeviceName() != null) n = r.getScanRecord().getDeviceName();
                if (n == null || !(n.startsWith("DSPlayer") || n.startsWith("DS Player"))) return;
                for (BluetoothDevice x : found) if (x.getAddress().equals(d.getAddress())) return;
                found.add(d);
                final String label = n;
                ui.post(() -> names.add(label));
            }
        };
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Procurando DS Player…")
                .setAdapter(names, (di, which) -> { try { sc.stopScan(cb); } catch (Exception ignored) {} connectTo(found.get(which)); })
                .setNegativeButton("Cancelar", (di, w) -> { try { sc.stopScan(cb); } catch (Exception ignored) {} })
                .create();
        dlg.show();
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(null, st, cb);
        ui.postDelayed(() -> { try { sc.stopScan(cb); } catch (Exception ignored) {} if (dlg.isShowing() && found.isEmpty()) dlg.setTitle("Nenhum DS Player encontrado"); }, 10000);
    }

    // ---------- conexão ----------
    void connectTo(BluetoothDevice d) {
        lastDevice = d; userClosed = false;
        if (gatt != null) { gatt.close(); gatt = null; }
        log("conectando em " + d.getName());
        if (Build.VERSION.SDK_INT >= 23) gatt = d.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
        else gatt = d.connectGatt(this, false, cb);
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log("conectado (status " + status + ")");
                ui.postDelayed(() -> { if (!g.requestMtu(64)) g.discoverServices(); }, 300);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                log("desconectou (status " + status + ")");
                chr = null; writing = false; synchronized (queue) { queue.clear(); }
                g.close(); if (gatt == g) gatt = null;
                js("onDisconnected", "");
                if (!userClosed && lastDevice != null) ui.postDelayed(() -> { if (gatt == null && !userClosed) { log("reconectando…"); connectTo(lastDevice); } }, 2000);
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            log("MTU " + mtu);
            g.discoverServices();
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { js("onError", "Serviço DEAF não encontrado"); g.disconnect(); return; }
            chr = s.getCharacteristic(CHR);
            if (chr == null) { js("onError", "Canal DEAF não encontrado"); g.disconnect(); return; }
            boolean ok = g.setCharacteristicNotification(chr, true);
            log("avisos ligados no Android: " + ok);
            BluetoothGattDescriptor d = chr.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
                log("descritor de aviso gravado");
            } else log("aparelho sem descritor (normal neste modelo)");
            ui.postDelayed(() -> js("onConnected", g.getDevice().getName()), 300);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            js("onData", toHex(c.getValue()));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            js("onData", toHex(v));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != 0) log("escrita status " + status);
            ui.postDelayed(() -> { writing = false; pump(); }, 30);
        }
    };

    void pump() {
        if (writing || gatt == null || chr == null) return;
        byte[] next;
        synchronized (queue) { next = queue.poll(); }
        if (next == null) return;
        writing = true;
        int type = (chr.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = gatt.writeCharacteristic(chr, next, type) == BluetoothStatusCodes.SUCCESS;
        } else {
            chr.setWriteType(type);
            chr.setValue(next);
            ok = gatt.writeCharacteristic(chr);
        }
        if (!ok) { writing = false; synchronized (queue) { queue.addFirst(next); } ui.postDelayed(this::pump, 60); }
        else if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ui.postDelayed(() -> { writing = false; pump(); }, 40);
        // trava de segurança: se o aparelho não confirmar a escrita, libera a fila
        final byte[] sent = next;
        ui.postDelayed(() -> { if (writing && sent != null) { writing = false; pump(); } }, 800);
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.__ble&&window.__ble.onBack&&window.__ble.onBack()", r -> {
            if (!"true".equals(r)) superBack();
        });
    }
    void superBack() { super.onBackPressed(); }
    @Override protected void onDestroy() {
        userClosed = true;
        if (gatt != null) { gatt.disconnect(); gatt.close(); }
        super.onDestroy();
    }

    static String toHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 255));
        return sb.toString();
    }
    static byte[] fromHex(String h) {
        h = h.replace(" ", "");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
