package com.lanone.phototag;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * PhotoTag 手机发图端（纯 framework，无 AndroidX 依赖）
 * 协议：每包 = [命令字节][数据...]，数据每包最多 239 字节
 *   0x01 [w][h][flags]  START
 *   0x02 [...数据...]   DATA（黑白层 2888 + 红色层 2888）
 *   0x03                COMMIT
 *   0x04                CLEAR
 */
public class MainActivity extends Activity {

    static final int S = 152;
    static final int STRIDE = 19;
    static final int LAYER = STRIDE * S;   // 2888
    static final int IMG = LAYER * 2;      // 5776

    static final UUID SVC = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CH_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CH_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int CMD_START = 0x01, CMD_DATA = 0x02, CMD_COMMIT = 0x03, CMD_CLEAR = 0x04;

    static final int PH_IDLE = 0, PH_START = 1, PH_DATA = 2, PH_COMMIT = 3, PH_DONE = 4;

    // ---- UI ----
    TextView logView, lblGamma, lblContrast, lblRed;
    ImageView preview;
    ProgressBar bar;
    Button btnPick, btnConnect, btnSend, btnClear;

    // ---- 状态 ----
    final Handler ui = new Handler(Looper.getMainLooper());
    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    int mtuSize = 23;

    Bitmap srcBmp;
    byte[] bwLayer, rdLayer;
    byte[] payload;
    int off = 0;
    int phase = PH_IDLE;

    float gGamma = 1.15f;
    float gContrast = 1.15f;
    int gRed = 45;

    Runnable pendingRender;

    // ================= 生命周期 =================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        TextView title = new TextView(this);
        title.setText("PhotoTag 发图");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(30, 30, 30));
        root.addView(title, lp(-1, -2));

        btnPick = mkButton("1. 选择照片", root);
        btnPick.setOnClickListener(v -> pickPhoto());

        preview = new ImageView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(240), dp(240));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(8);
        plp.bottomMargin = dp(8);
        preview.setBackgroundColor(Color.WHITE);
        root.addView(preview, plp);

        lblGamma = mkLabel("亮度 Gamma  1.15（>1 提亮暗部）", root);
        addSeek(root, 40, 220, 115, p -> {
            gGamma = (0.4f + p / 100f);
            lblGamma.setText(String.format("亮度 Gamma  %.2f（>1 提亮暗部）", gGamma));
            scheduleRender();
        });

        lblContrast = mkLabel("对比度  1.15", root);
        addSeek(root, 50, 250, 115, p -> {
            gContrast = (0.5f + p / 100f);
            lblContrast.setText(String.format("对比度  %.2f", gContrast));
            scheduleRender();
        });

        lblRed = mkLabel("红色阈值  45（调大 = 更挑剔）", root);
        addSeek(root, 10, 120, 45, p -> {
            gRed = p;
            lblRed.setText("红色阈值  " + p + "（调大 = 更挑剔）");
            scheduleRender();
        });

        btnConnect = mkButton("2. 连接设备（PhotoTag-xxxx）", root);
        btnConnect.setOnClickListener(v -> connect());

        btnSend = mkButton("3. 发送到屏幕（约 30 秒）", root);
        btnSend.setOnClickListener(v -> startSend());

        btnClear = mkButton("清屏（全白）", root);
        btnClear.setOnClickListener(v -> clearScreen());

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(10));
        blp.topMargin = dp(10);
        root.addView(bar, blp);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.rgb(60, 60, 60));
        logView.setPadding(0, dp(10), 0, dp(10));
        root.addView(logView, lp(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();

        post("协议版本：每包 [命令][数据]，数据 239 字节/包，共 " + IMG + " 字节");
        post("先选照片调参数，再连设备发。");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    // ================= 小工具 =================
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    LinearLayout.LayoutParams lp(int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(6);
        return p;
    }

    void post(String s) {
        ui.post(() -> {
            logView.append(s + "\n");
            int sc = logView.getScrollY() + logView.getHeight();
            if (logView.getParent() instanceof ScrollView) {
                ((ScrollView) logView.getParent()).post(() -> ((ScrollView) logView.getParent()).fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    Button mkButton(String text, LinearLayout root) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        root.addView(b, lp(-1, -2));
        return b;
    }

    TextView mkLabel(String text, LinearLayout root) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Color.rgb(80, 80, 80));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        root.addView(t, p);
        return t;
    }

    interface OnSeek { void on(int progress); }

    void addSeek(LinearLayout root, int min, int max, int cur, OnSeek cb) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { cb.on(min + p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb, lp(-1, -2));
    }

    // ================= 图片 =================
    void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, 101);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 101 && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Bitmap bmp = loadBitmap(data.getData());
                if (bmp == null) { post("解码失败"); return; }
                srcBmp = bmp;
                convert(bmp);
                post("已选图 " + bmp.getWidth() + "x" + bmp.getHeight());
            } catch (Exception e) {
                post("读图出错：" + e);
            }
        }
    }

    Bitmap loadBitmap(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(in, null, o);
        if (in != null) in.close();

        int sample = 1;
        while (o.outWidth / (sample * 2) >= 512 && o.outHeight / (sample * 2) >= 512) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        in = getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
        if (in != null) in.close();
        return bmp;
    }

    void scheduleRender() {
        if (srcBmp == null) return;
        if (pendingRender != null) ui.removeCallbacks(pendingRender);
        pendingRender = () -> convert(srcBmp);
        ui.postDelayed(pendingRender, 120);
    }

    static void setBit(byte[] a, int x, int y, boolean v) {
        int i = y * STRIDE + (x >> 3);
        int b = 7 - (x & 7);
        if (v) a[i] = (byte) (a[i] | (1 << b));
        else a[i] = (byte) (a[i] & ~(1 << b));
    }

    void convert(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return;
        int m = Math.min(w, h);
        Bitmap sq = Bitmap.createBitmap(src, (w - m) / 2, (h - m) / 2, m, m);
        Bitmap small = Bitmap.createScaledBitmap(sq, S, S, true);

        int[] px = new int[S * S];
        small.getPixels(px, 0, S, 0, 0, S, S);

        float[][] lum = new float[S][S];
        boolean[][] red = new boolean[S][S];

        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int c = px[y * S + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (r - Math.max(g, b) > gRed && r > 70) red[y][x] = true;

                float v = 0.299f * r + 0.587f * g + 0.114f * b;
                v = (v - 128f) * gContrast + 128f;
                if (gGamma != 1f) {
                    float n = Math.max(0f, v) / 255f;
                    v = 255f * (float) Math.pow(n, 1.0 / gGamma);
                }
                lum[y][x] = Math.max(0f, Math.min(255f, v));
            }
        }

        byte[] bw = new byte[LAYER];
        byte[] rd = new byte[LAYER];
        Arrays.fill(bw, (byte) 0xFF);       // 1 = 白

        float[][] err = new float[S][S];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                if (red[y][x]) { setBit(rd, x, y, true); continue; }
                float v = Math.max(0f, Math.min(255f, lum[y][x] + err[y][x]));
                boolean black = v < 128f;
                float outv = black ? 0f : 255f;
                if (black) setBit(bw, x, y, false);
                float e = v - outv;
                if (x + 1 < S) err[y][x + 1] += e * 7 / 16f;
                if (y + 1 < S) {
                    if (x > 0) err[y + 1][x - 1] += e * 3 / 16f;
                    err[y + 1][x] += e * 5 / 16f;
                    if (x + 1 < S) err[y + 1][x + 1] += e * 1 / 16f;
                }
            }
        }

        bwLayer = bw;
        rdLayer = rd;
        updatePreview();
    }

    void updatePreview() {
        if (bwLayer == null || rdLayer == null) return;
        int scale = 2;
        int W = S * scale;
        Bitmap bmp = Bitmap.createBitmap(W, W, Bitmap.Config.ARGB_8888);
        int[] out = new int[W * W];
        for (int y = 0; y < W; y++) {
            int sy = y / scale;
            for (int x = 0; x < W; x++) {
                int sx = x / scale;
                int i = sy * STRIDE + (sx >> 3);
                int b = 7 - (sx & 7);
                int c;
                if ((((rdLayer[i] & 0xFF) >> b) & 1) != 0) c = Color.rgb(200, 30, 30);
                else if ((((bwLayer[i] & 0xFF) >> b) & 1) == 0) c = Color.rgb(20, 20, 20);
                else c = Color.WHITE;
                out[y * W + x] = c;
            }
        }
        bmp.setPixels(out, 0, W, 0, 0, W, W);
        preview.setImageBitmap(bmp);
    }

    // ================= 权限 =================
    boolean ensurePerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), 201);
        post("请先允许蓝牙权限，允许后再点一次连接");
        return false;
    }

    // ================= BLE =================
    final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String name = null;
            try { name = d.getName(); } catch (SecurityException ignored) {}
            if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
            if (name != null && name.startsWith("PhotoTag")) {
                if (scanner != null) scanner.stopScan(this);
                post("找到 " + name + "，连接中…");
                try {
                    gatt = d.connectGatt(MainActivity.this, false, cb, BluetoothDevice.TRANSPORT_LE);
                } catch (SecurityException e) {
                    post("连接失败：缺权限");
                }
            }
        }

        @Override
        public void onScanFailed(int code) {
            post("扫描失败 code=" + code);
        }
    };

    void connect() {
        if (!ensurePerms()) return;
        if (adapter == null) { post("本机没有蓝牙适配器"); return; }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 202);
            return;
        }
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { post("扫描器不可用，检查蓝牙是否打开"); return; }

        ScanSettings st = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try { scanner.startScan(null, st, scanCb); } catch (SecurityException e) { post("缺扫描权限"); return; }
        post("扫描中（15 秒）…");
        ui.postDelayed(() -> {
            if (scanner != null) {
                try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
                scanner = null;
            }
        }, 15000);
    }

    int dataPerPacket() {
        return Math.min(239, Math.max(16, mtuSize - 4));
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                post("已连接，发现服务…");
                try { g.discoverServices(); } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                post("已断开（status=" + status + "）");
                gatt = null;
                phase = PH_IDLE;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SVC);
            if (s == null) { post("没找到 PhotoTag 服务"); return; }
            BluetoothGattCharacteristic tx = s.getCharacteristic(CH_TX);
            if (tx != null) {
                try {
                    g.setCharacteristicNotification(tx, true);
                    BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                } catch (SecurityException ignored) {}
            }
            post("服务就绪，请求 MTU…");
            ui.postDelayed(() -> {
                try { g.requestMtu(517); } catch (SecurityException ignored) {}
            }, 300);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mtuSize = mtu;
            post("MTU = " + mtu + "，每包数据 " + dataPerPacket() + " 字节");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post("写失败 status=" + status + " @phase=" + phase);
                phase = PH_IDLE;
                return;
            }
            advance(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            String t = "";
            try { t = new String(c.getValue()); } catch (Exception ignored) {}
            post("设备: " + t);
        }
    };

    void advance(BluetoothGatt g) {
        if (phase == PH_START) {
            phase = PH_DATA;
            ui.postDelayed(() -> pump(g), 300);     // 等 START 的通知发完
        } else if (phase == PH_DATA) {
            ui.postDelayed(() -> pump(g), 20);      // 必须留间隔
        } else if (phase == PH_COMMIT) {
            phase = PH_DONE;
            bar.setProgress(100);
            post("已发送，屏幕刷新约 30 秒");
        }
    }

    void pump(BluetoothGatt g) {
        if (g == null) return;
        BluetoothGattService s = g.getService(SVC);
        if (s == null) return;
        BluetoothGattCharacteristic ch = s.getCharacteristic(CH_RX);
        if (ch == null) return;

        byte[] v;
        if (phase == PH_START) {
            v = new byte[]{CMD_START, (byte) S, (byte) S, 0};
        } else if (phase == PH_DATA) {
            if (payload == null || off >= payload.length) {
                phase = PH_COMMIT;
                pump(g);
                return;
            }
            int n = Math.min(dataPerPacket(), payload.length - off);
            v = new byte[n + 1];
            v[0] = CMD_DATA;
            System.arraycopy(payload, off, v, 1, n);
            off += n;
            bar.setProgress(off * 100 / IMG);
        } else if (phase == PH_COMMIT) {
            v = new byte[]{CMD_COMMIT};
        } else {
            return;
        }

        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        ch.setValue(v);
        boolean ok;
        try {
            ok = g.writeCharacteristic(ch);
        } catch (SecurityException e) {
            post("缺连接权限");
            phase = PH_IDLE;
            return;
        }
        if (!ok) ui.postDelayed(() -> pump(g), 15);
    }

    void startSend() {
        if (gatt == null) { post("先点“连接设备”"); return; }
        if (bwLayer == null || rdLayer == null) { post("先选一张照片"); return; }
        payload = new byte[IMG];
        System.arraycopy(bwLayer, 0, payload, 0, LAYER);
        System.arraycopy(rdLayer, 0, payload, LAYER, LAYER);
        off = 0;
        phase = PH_START;
        bar.setProgress(0);
        post("开始发送 " + IMG + " 字节…");
        pump(gatt);
    }

    void clearScreen() {
        if (gatt == null) { post("先连接设备"); return; }
        bwLayer = new byte[LAYER];
        Arrays.fill(bwLayer, (byte) 0xFF);
        rdLayer = new byte[LAYER];
        updatePreview();
        startSend();
    }
}