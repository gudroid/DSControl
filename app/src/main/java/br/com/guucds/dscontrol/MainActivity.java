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
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.net.Uri;
import android.content.Intent;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.view.View;
import android.content.SharedPreferences;
import android.util.Base64;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
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
    ValueCallback<Uri[]> fileCb;
    static final String LIC_PUB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEMB5OzilAyrJhPDuE4jAFJFglV2mTC5JZwZEkbBh7Y45ZUJQ4hL5ne4uF/pxgXsw4cRpsKopP5QtHQaZ1fI8pFA==";
    static byte[] hexToBytes(String h){byte[] o=new byte[h.length()/2];for(int i=0;i<o.length;i++)o[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16);return o;}
    static byte[] toDer(byte[] raw){BigInteger r=new BigInteger(1,Arrays.copyOfRange(raw,0,32)),s=new BigInteger(1,Arrays.copyOfRange(raw,32,64));byte[] rb=r.toByteArray(),sb=s.toByteArray();byte[] o=new byte[6+rb.length+sb.length];o[0]=0x30;o[1]=(byte)(4+rb.length+sb.length);o[2]=2;o[3]=(byte)rb.length;System.arraycopy(rb,0,o,4,rb.length);o[4+rb.length]=2;o[5+rb.length]=(byte)sb.length;System.arraycopy(sb,0,o,6+rb.length,sb.length);return o;}

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // tela não apaga no carro
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            // permite escolher a capa na galeria
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try { startActivityForResult(Intent.createChooser(i, "Escolher capa"), 77); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
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
        // confere a assinatura da chave de licença (apenas verificação, com a chave pública embutida)
        @JavascriptInterface public boolean licenseVerify(String payloadHex, String sigHex) {
            try {
                byte[] pay = hexToBytes(payloadHex), raw = hexToBytes(sigHex);
                byte[] der = toDer(raw);
                PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(LIC_PUB, Base64.DEFAULT)));
                Signature v = Signature.getInstance("SHA256withECDSA");
                v.initVerify(pk); v.update(pay); return v.verify(der);
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void fullscreen(final boolean on){
            ui.post(()->{ View d=getWindow().getDecorView();
              if(on) d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
              else d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); });
        }
        @JavascriptInterface public String deviceId() {
            try { String a = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                  return a != null ? a : Build.FINGERPRINT; } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void connect() { ui.post(MainActivity.this::startScan); }
        @JavascriptInterface public void autoConnect() { ui.post(()->{
            try{ String a=getSharedPreferences("dsc",0).getString("lastAddr",null);
                 if(a!=null && adapter!=null && hasPerms()){ connectTo(adapter.getRemoteDevice(a)); } else startScan(); }catch(Exception e){ startScan(); } }); }
        @JavascriptInterface public void openUrl(String u){ try{ startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }catch(Exception e){} }
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
                android.bluetooth.le.ScanRecord rec = r.getScanRecord();
                String n = (rec != null && rec.getDeviceName() != null) ? rec.getDeviceName() : d.getName();
                boolean byName = n != null && (n.startsWith("DSPlayer") || n.startsWith("DS Player"));
                boolean bySvc = false;
                if (rec != null && rec.getServiceUuids() != null) {
                    for (android.os.ParcelUuid pu : rec.getServiceUuids()) if (pu.getUuid().equals(SVC)) { bySvc = true; break; }
                }
                if (!byName && !bySvc) return;
                for (BluetoothDevice x : found) if (x.getAddress().equals(d.getAddress())) return;
                found.add(d);
                String addr = d.getAddress();
                final String label = (n != null ? n : "DS Player") + (byName ? "" : " [" + (addr.length() >= 5 ? addr.substring(addr.length() - 5) : addr) + "]");
                ui.post(() -> names.add(label));
            }
        };
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Procurando DS Player…")
                .setAdapter(names, (di, which) -> { try { sc.stopScan(cb); } catch (Exception ignored) {} connectTo(found.get(which)); })
                .setNegativeButton("Cancelar", (di, w) -> { try { sc.stopScan(cb); } catch (Exception ignored) {} })
                .create();
        dlg.show();
        // inclui aparelhos já pareados/conectados (que não aparecem na busca por já estarem em uso)
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String n = d.getName();
                if (n != null && (n.startsWith("DSPlayer") || n.startsWith("DS Player"))) {
                    boolean dup = false;
                    for (BluetoothDevice x : found) if (x.getAddress().equals(d.getAddress())) dup = true;
                    if (!dup) { found.add(d); final String label = n + " (pareado)"; ui.post(() -> names.add(label)); }
                }
            }
        } catch (Exception ignored) {}
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(null, st, cb);
        ui.postDelayed(() -> { try { sc.stopScan(cb); } catch (Exception ignored) {} if (dlg.isShowing()) dlg.setTitle(found.isEmpty() ? "Nenhum DS Player encontrado" : "Toque no seu DS Player"); }, 10000);
    }

    // ---------- conexão ----------
    void connectTo(BluetoothDevice d) {
        lastDevice = d; userClosed = false;
        try{ getSharedPreferences("dsc",0).edit().putString("lastAddr", d.getAddress()).apply(); }catch(Exception e){}
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
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 77 && fileCb != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCb.onReceiveValue(u != null ? new Uri[]{u} : null);
            fileCb = null;
        }
    }
    // girar a tela não recria nada: a conexão continua
    @Override public void onConfigurationChanged(android.content.res.Configuration c) { super.onConfigurationChanged(c); }
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
