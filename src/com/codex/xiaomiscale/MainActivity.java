package com.codex.xiaomiscale;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends Activity implements ScaleGattClient.Listener {
    private static final int REQUEST_LOCATION = 100;
    private static final ParcelUuid SCALE_UUID = ParcelUuid.fromString(
            "0000181d-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private TextView weightView;
    private TextView unitView;
    private TextView stateView;
    private TextView deviceView;
    private TextView packetView;
    private TextView gattStatusView;
    private TextView hintView;
    private TextView currentUserView;
    private TextView changeView;
    private TextView historyCountView;
    private TextView updatedView;
    private TextView bmiView;
    private TextView targetView;
    private BmiBandView bmiBandView;
    private float activeWeightKg;
    private LinearLayout memberStrip;
    private LinearLayout dashboardChartHolder;
    private TextView dashboardTrendTitle;
    private Spinner trendRangeSpinner;
    private Button scanButton;
    private Button syncButton;
    private Button historyButton;
    private Button usersButton;
    private Button trendButton;
    private long packetCount;
    private ScaleDatabase database;
    private ScaleGattClient gattClient;
    private BluetoothDevice lastDevice;
    private String lastAddress = "unknown";
    private long lastScaleTimeMillis;
    private long lastAutoGattStart;
    private boolean foreground;
    private long currentUserId = -1L;
    private int dashboardTrendDays = 7;
    private SharedPreferences preferences;
    private final Map<String, BluetoothDevice> nearbyDevices =
            new LinkedHashMap<String, BluetoothDevice>();
    private final Map<String, Integer> nearbyRssi = new LinkedHashMap<String, Integer>();
    private boolean babyMode;
    private float babyAdultKg;
    private boolean babyReadyForSecond;
    private long babyUserId;
    private boolean manualMemberSelection;
    private boolean measurementSessionActive;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 251));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        database = new ScaleDatabase(this);
        preferences = getSharedPreferences("scale_settings", MODE_PRIVATE);
        buildUi();
        gattClient = new ScaleGattClient(this, this);
        updateHistoryButton();
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        ensureReadyAndScan();
    }

    @Override protected void onPause() {
        foreground = false;
        stopScan();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopScan();
        if (gattClient != null) gattClient.close();
        if (database != null) database.close();
        super.onDestroy();
    }

    private void buildUi() {
        final int green = Color.rgb(0, 190, 131);
        final int dark = Color.rgb(39, 45, 51);
        final int muted = Color.rgb(132, 141, 148);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 247));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(26), 0, dp(18), 0);
        header.setBackgroundColor(green);
        TextView title = text("小米体重秤", 27, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, wrap());
        updatedView = text("  ·  等待体重秤", 17, Color.argb(210, 255, 255, 255));
        header.addView(updatedView, wrap());
        header.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        syncButton = actionButton("同步秤内历史", true);
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startGattSync(true); }
        });
        LinearLayout.LayoutParams headerSyncParams = new LinearLayout.LayoutParams(dp(148), dp(46));
        headerSyncParams.setMargins(0, 0, dp(10), 0);
        header.addView(syncButton, headerSyncParams);
        Button deviceButton = actionButton("设备信息", true);
        deviceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDeviceInfo(); }
        });
        header.addView(deviceButton, new LinearLayout.LayoutParams(dp(126), dp(46)));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        memberStrip = new LinearLayout(this);
        memberStrip.setGravity(Gravity.CENTER_VERTICAL);
        memberStrip.setPadding(dp(22), dp(7), dp(22), dp(7));
        memberStrip.setBackgroundColor(Color.WHITE);
        renderMemberStrip();
        root.addView(memberStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(18), dp(16), dp(18), dp(18));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(32), dp(22), dp(32), dp(20));
        hero.setBackground(rounded(green, 18));
        hero.setElevation(dp(2));
        currentUserView = text("未识别用户", 22, Color.WHITE);
        currentUserView.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(currentUserView, wrap());
        updatedView = text("请踩上体重秤", 15, Color.argb(205, 255, 255, 255));
        hero.addView(updatedView, wrap());

        LinearLayout weightLine = new LinearLayout(this);
        weightLine.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        weightView = text("--.--", 88, Color.WHITE);
        weightView.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        weightLine.addView(weightView, wrap());
        unitView = text("kg", 25, Color.argb(220, 255, 255, 255));
        LinearLayout.LayoutParams unitParams = wrap();
        unitParams.setMargins(dp(8), 0, 0, dp(16));
        weightLine.addView(unitView, unitParams);
        LinearLayout.LayoutParams weightParams = matchWidthWrap();
        weightParams.setMargins(0, dp(14), 0, 0);
        hero.addView(weightLine, weightParams);

        stateView = text("正在搜索体重秤…", 23, Color.WHITE);
        stateView.setGravity(Gravity.CENTER);
        stateView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stateParams = matchWidthWrap();
        stateParams.setMargins(0, dp(5), 0, 0);
        hero.addView(stateView, stateParams);
        hintView = text("请踩上体重秤以唤醒蓝牙广播", 16,
                Color.argb(215, 255, 255, 255));
        hintView.setGravity(Gravity.CENTER);
        hintView.setMaxLines(2);
        LinearLayout.LayoutParams hintParams = matchWidthWrap();
        hintParams.setMargins(0, dp(9), 0, 0);
        hero.addView(hintView, hintParams);

        bmiView = text("BMI -- · 请完善成员身高", 21, Color.WHITE);
        bmiView.setGravity(Gravity.CENTER);
        bmiView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams bmiParams = matchWidthWrap();
        bmiParams.setMargins(0, dp(24), 0, 0);
        hero.addView(bmiView, bmiParams);
        targetView = text("目标体重：未设置", 16, Color.argb(220, 255, 255, 255));
        targetView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams targetParams = matchWidthWrap();
        targetParams.setMargins(0, dp(6), 0, 0);
        hero.addView(targetView, targetParams);
        bmiBandView = new BmiBandView(this);
        hero.addView(bmiBandView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setGravity(Gravity.CENTER);
        Button manualButton = actionButton("手动记体重", true);
        manualButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editRecord(null); }
        });
        quickActions.addView(manualButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button babyButton = actionButton("抱婴称重", true);
        babyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBabyModePicker(); }
        });
        LinearLayout.LayoutParams babyParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        babyParams.setMargins(dp(10), 0, 0, 0);
        quickActions.addView(babyButton, babyParams);
        hero.addView(quickActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        body.addView(hero, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.43f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.57f);
        rightParams.setMargins(dp(16), 0, 0, 0);
        body.addView(right, rightParams);

        LinearLayout summary = card();
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout changeBox = metricBox("较上次变化", "暂无对比", green);
        changeView = (TextView) changeBox.getChildAt(0);
        summary.addView(changeBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(232, 235, 237));
        summary.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(58)));
        LinearLayout countBox = metricBox("历史记录", database.count() + " 条", dark);
        historyCountView = (TextView) countBox.getChildAt(0);
        countBox.setClickable(true);
        countBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHistory(); }
        });
        summary.addView(countBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        right.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(98)));

        LinearLayout chartCard = card();
        chartCard.setPadding(dp(18), dp(12), dp(18), dp(8));
        LinearLayout chartHeader = new LinearLayout(this);
        chartHeader.setGravity(Gravity.CENTER_VERTICAL);
        dashboardTrendTitle = text("最近 7 天体重趋势", 20, dark);
        dashboardTrendTitle.setTypeface(Typeface.DEFAULT_BOLD);
        chartHeader.addView(dashboardTrendTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        trendRangeSpinner = new Spinner(this);
        String[] trendRanges = new String[]{"7天趋势", "30天趋势", "全部趋势"};
        ArrayAdapter<String> trendAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, trendRanges);
        trendRangeSpinner.setAdapter(trendAdapter);
        int savedTrendPosition = preferences.getInt("dashboard_trend_position", 0);
        if (savedTrendPosition < 0 || savedTrendPosition >= trendRanges.length) {
            savedTrendPosition = 0;
        }
        trendRangeSpinner.setSelection(savedTrendPosition);
        trendRangeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                dashboardTrendDays = position == 0 ? 7 : (position == 1 ? 30 : 0);
                preferences.edit().putInt("dashboard_trend_position", position).apply();
                dashboardTrendTitle.setText(position == 0 ? "最近 7 天体重趋势" :
                        (position == 1 ? "最近 30 天体重趋势" : "全部体重趋势"));
                refreshDashboardChart();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        chartHeader.addView(trendRangeSpinner, new LinearLayout.LayoutParams(dp(150), dp(46)));
        chartCard.addView(chartHeader, matchWidthWrap());
        dashboardChartHolder = new LinearLayout(this);
        chartCard.addView(dashboardChartHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams chartCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        chartCardParams.setMargins(0, dp(12), 0, dp(12));
        right.addView(chartCard, chartCardParams);
        refreshDashboardChart();

        deviceView = text("设备：等待发现\n地址：--\n信号：--", 17, dark);
        packetView = text("--", 15, dark);
        packetView.setTypeface(Typeface.MONOSPACE);
        packetView.setTextIsSelectable(true);
        gattStatusView = text("历史同步：尚未连接", 16, muted);

        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        restoreLatestMeasurement();
    }

    private void ensureReadyAndScan() {
        if (adapter == null) {
            setProblem("此设备没有可用的蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            setProblem("蓝牙尚未开启，请先在系统设置中开启蓝牙");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            stateView.setText("需要位置权限才能扫描蓝牙体重秤");
            return;
        }
        if (Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) == Settings.Secure.LOCATION_MODE_OFF) {
            setProblem("系统定位服务已关闭；Android 8.1 扫描 BLE 需要开启定位服务");
            return;
        }
        startScan();
    }

    private void startScan() {
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setProblem("无法启动低功耗蓝牙扫描");
            return;
        }
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, callback);
            scanning = true;
            stateView.setText("正在搜索体重秤…");
            stateView.setTextColor(Color.WHITE);
            hintView.setText("请踩上体重秤以唤醒蓝牙广播");
            if (scanButton != null) scanButton.setText("正在扫描");
        } catch (RuntimeException error) {
            setProblem("扫描启动失败：" + error.getClass().getSimpleName());
        }
    }

    private void stopScan() {
        if (!scanning) return;
        try {
            if (scanner != null) scanner.stopScan(callback);
        } catch (RuntimeException ignored) {}
        scanning = false;
        if (scanButton != null) scanButton.setText("重新扫描");
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            consume(result);
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) consume(result);
        }

        @Override public void onScanFailed(final int errorCode) {
            scanning = false;
            runOnUiThread(new Runnable() {
                @Override public void run() { setProblem("蓝牙扫描失败，错误码：" + errorCode); }
            });
        }
    };

    private void consume(final ScanResult scanResult) {
        ScanRecord record = scanResult.getScanRecord();
        if (record == null) return;
        byte[] payload = record.getServiceData(SCALE_UUID);
        final MiScaleParser.Result parsed = MiScaleParser.parse(payload);
        if (parsed == null) return;
        final BluetoothDevice device = scanResult.getDevice();
        final int rssi = scanResult.getRssi();
        final String foundAddress = device == null ? "unknown" : device.getAddress();
        if (device != null) {
            nearbyDevices.put(foundAddress, device);
            nearbyRssi.put(foundAddress, rssi);
        }
        String boundAddress = preferences.getString("bound_device", "");
        if (!TextUtils.isEmpty(boundAddress) && !boundAddress.equalsIgnoreCase(foundAddress)) return;
        packetCount++;
        final long count = packetCount;
        lastDevice = device;
        if (device != null) lastAddress = device.getAddress();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                showMeasurement(parsed, device, rssi, count);
            }
        });
    }

    private void showMeasurement(MiScaleParser.Result result, BluetoothDevice device,
                                 int rssi, long count) {
        String name = device == null ? null : device.getName();
        if (TextUtils.isEmpty(name)) name = "XMTZC01HM / 未命名设备";
        String address = device == null ? "--" : device.getAddress();
        String timeText = TextUtils.isEmpty(result.measurementTime) ? "" :
                "\n秤内时间：" + result.measurementTime;
        deviceView.setText("设备：" + name + "\n地址：" + address + "\n信号：" + rssi +
                " dBm\n数据包：" + count + timeText);
        packetView.setText(result.rawHex);
        if (handleBabyMeasurement(result)) return;

        // A manually selected member stays pinned while the scale is only repeating the
        // previous stable/removed advertisement. A genuinely new weighing session starts
        // with an unstable, non-removed frame and is then allowed to take over the dashboard.
        if (manualMemberSelection && !measurementSessionActive) {
            if (result.removed || result.stable) return;
            manualMemberSelection = false;
            measurementSessionActive = true;
        } else if (!result.removed && !result.stable) {
            measurementSessionActive = true;
        }

        weightView.setText(String.format(Locale.US, "%.2f", result.displayWeight));
        unitView.setText(result.sourceUnit);

        if (measurementSessionActive && !result.removed && !result.stable) {
            ScaleDatabase.User liveUser = database.matchedUser(result.weightKg);
            currentUserId = liveUser == null ? -1L : liveUser.id;
            currentUserView.setText(liveUser == null ? "未识别用户" : liveUser.name);
            activeWeightKg = result.weightKg;
            updateHealthStatus(liveUser, result.weightKg);
        }

        if (result.removed) {
            // Removed advertisements can arrive immediately after startup and replace the
            // displayed weight. Keep the member label in sync with that same weight instead
            // of leaving the member restored from a different, newer database record.
            ScaleDatabase.User removedUser = database.matchedUser(result.weightKg);
            currentUserId = removedUser == null ? -1L : removedUser.id;
            currentUserView.setText(removedUser == null ? "未识别" : removedUser.name);
            activeWeightKg = result.weightKg;
            updateHealthStatus(removedUser, result.weightKg);
            refreshDashboardChart();
            stateView.setText("已离开体重秤");
            stateView.setTextColor(Color.WHITE);
            hintView.setText("最后一次检测值已保留");
            measurementSessionActive = false;
        } else if (result.stable) {
            stateView.setText("● 已稳定");
            stateView.setTextColor(Color.WHITE);
            boolean inserted = database.insert(address, result, "live",
                    preferences.getBoolean("merge_30_seconds", true) ? 30 : 0);
            updateHistoryButton();
            ScaleDatabase.User matched = database.matchedUser(result.weightKg);
            long matchedId = matched == null ? 0L : matched.id;
            currentUserId = matched == null ? -1L : matched.id;
            currentUserView.setText(matched == null ? "未识别用户" : matched.name);
            activeWeightKg = result.weightKg;
            updateHealthStatus(matched, result.weightKg);
            updatedView.setText(new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                    .format(new Date(System.currentTimeMillis())));
            Float change = database.latestChangeKg(matchedId);
            StringBuilder saved = new StringBuilder(inserted ? "稳定体重已保存" :
                    "稳定体重已保存（重复数据已忽略）");
            saved.append(" · ").append(matched == null ? "未识别用户" : matched.name);
            if (change != null) saved.append(" · ")
                    .append(ScaleDatabase.formatChange(change, result.sourceUnit));
            hintView.setText(saved.toString());
            if (changeView != null) {
                changeView.setText(change == null ? "首次记录" :
                        String.format(Locale.CHINA, "%+.2f %s",
                                ScaleDatabase.convertFromKg(change, result.sourceUnit),
                                result.sourceUnit));
                changeView.setTextColor(change != null && change > 0
                        ? Color.rgb(255, 91, 80) : Color.rgb(0, 185, 120));
            }
            refreshDashboardChart();
            lastScaleTimeMillis = result.measurementTimeMillis;
            long now = System.currentTimeMillis();
            if (now - lastAutoGattStart >= 5 * 60 * 1000L) {
                lastAutoGattStart = now;
                startGattSync(false);
            }
        } else {
            stateView.setText("测量中…");
            stateView.setTextColor(Color.WHITE);
            hintView.setText("请保持站立，等待数字稳定");
        }
    }

    private boolean handleBabyMeasurement(MiScaleParser.Result result) {
        if (!babyMode) return false;
        if (result.removed) {
            if (babyAdultKg > 0) {
                babyReadyForSecond = true;
                stateView.setText("成人已离秤");
                hintView.setText("请抱起宝宝，再次站上体重秤");
            }
            return true;
        }
        if (!result.stable) {
            stateView.setText(babyAdultKg == 0 ? "正在记录成人体重…" : "正在称量成人与宝宝…");
            return true;
        }
        if (babyAdultKg == 0) {
            babyAdultKg = result.weightKg;
            stateView.setText("成人体重已记录");
            hintView.setText(String.format(Locale.CHINA,
                    "成人 %.2f kg，请先离秤，再抱宝宝称量", babyAdultKg));
            return true;
        }
        if (!babyReadyForSecond) return true;
        float babyKg = result.weightKg - babyAdultKg;
        if (babyKg < 0.3f || babyKg > 40f) {
            stateView.setText("抱婴结果异常");
            hintView.setText("请确认第二次为成人抱着宝宝称量");
            return true;
        }
        ScaleDatabase.User babyUser = database.user(babyUserId);
        database.addManualRecord(babyUserId, System.currentTimeMillis(), babyKg, result.sourceUnit);
        currentUserId = babyUserId;
        currentUserView.setText(babyUser == null ? "宝宝" : babyUser.name);
        activeWeightKg = babyKg;
        weightView.setText(String.format(Locale.CHINA, "%.2f",
                ScaleDatabase.convertFromKg(babyKg, result.sourceUnit)));
        unitView.setText(result.sourceUnit);
        stateView.setText("抱婴称重完成");
        hintView.setText(String.format(Locale.CHINA, "成人与宝宝差值 %.2f kg，记录已保存", babyKg));
        updateHealthStatus(babyUser, babyKg);
        updateHistoryButton();
        refreshDashboardChart();
        babyMode = false;
        babyAdultKg = 0;
        babyReadyForSecond = false;
        return true;
    }

    private void showBabyModePicker() {
        if (babyMode) {
            babyMode = false;
            babyAdultKg = 0;
            babyReadyForSecond = false;
            stateView.setText("抱婴称重已取消");
            hintView.setText("请踩上体重秤开始普通测量");
            return;
        }
        final List<ScaleDatabase.User> users = database.users();
        if (users.isEmpty()) {
            Toast.makeText(this, "请先为宝宝添加一个家庭成员", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[users.size()];
        for (int i = 0; i < users.size(); i++) names[i] = users.get(i).name;
        AlertDialog.Builder babyBuilder = new AlertDialog.Builder(this)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        babyUserId = users.get(which).id;
                        babyMode = true;
                        babyAdultKg = 0;
                        babyReadyForSecond = false;
                        stateView.setText("抱婴称重：第一步");
                        hintView.setText("请成人单独站上体重秤，稳定后再离秤");
                    }
                });
        showDialogWithClose(babyBuilder, "选择宝宝成员");
    }

    private void startGattSync(boolean manual) {
        if (lastDevice == null) {
            Toast.makeText(this, "请先踩一下体重秤将其唤醒", Toast.LENGTH_LONG).show();
            return;
        }
        long difference = lastScaleTimeMillis > 0
                ? System.currentTimeMillis() - lastScaleTimeMillis : 0;
        boolean syncTime = difference >= 60 * 60 * 1000L;
        stopScan();
        syncButton.setEnabled(false);
        gattClient.start(lastDevice, syncTime);
        if (manual) Toast.makeText(this, "正在连接体重秤，请保持体重秤亮起",
                Toast.LENGTH_SHORT).show();
    }

    private void updateHistoryButton() {
        if (database == null) return;
        if (historyButton != null) historyButton.setText("历史记录");
        if (historyCountView != null) historyCountView.setText(database.count() + " 条");
    }

    private void showHistory() {
        List<ScaleDatabase.Record> records = database.latest(100);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(14));
        if (records.isEmpty()) {
            TextView empty = text("暂时没有稳定测量记录\n请踩上体重秤完成一次稳定测量",
                    19, Color.rgb(132, 141, 148));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(80), 0, dp(80));
            content.addView(empty, matchWidthWrap());
        } else for (final ScaleDatabase.Record record : records) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));
            row.setBackground(rounded(Color.rgb(247, 249, 249), 10));
            final TextView number = text(String.format(Locale.CHINA, "%.2f %s",
                    record.displayWeight, record.unit), 24, Color.rgb(0, 174, 120));
            number.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(number, new LinearLayout.LayoutParams(dp(175),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            String time = new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                    .format(new Date(record.measuredAt));
            String source = "history".equals(record.source) ? "秤内同步"
                    : "manual".equals(record.source) ? "手动记录" : "实时测量";
            TextView details = text(record.userName + "  ·  " + time + "\n" + source,
                    16, Color.rgb(91, 99, 105));
            details.setLineSpacing(dp(3), 1f);
            row.addView(details, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView delta = text(record.changeKg == null ? "首次记录" :
                            String.format(Locale.CHINA, "%+.2f %s",
                                    ScaleDatabase.convertFromKg(record.changeKg, record.unit),
                                    record.unit), 18, record.changeKg != null && record.changeKg > 0
                            ? Color.rgb(255, 91, 80) : Color.rgb(0, 185, 120));
            delta.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(delta, new LinearLayout.LayoutParams(dp(150),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            final SwipeRecordRow swipeRow = new SwipeRecordRow(this, row,
                    new SwipeRecordRow.Listener() {
                        @Override public void onEdit(SwipeRecordRow swipe) {
                            beginInlineRecordEdit(record, number, swipe);
                        }

                        @Override public void onDelete(SwipeRecordRow swipe) {
                            if (database.deleteRecord(record.id)) {
                                content.removeView(swipe);
                                updateHistoryButton();
                                refreshDashboardChart();
                                Toast.makeText(MainActivity.this, "记录已删除",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(68));
            rowParams.setMargins(0, 0, 0, dp(8));
            content.addView(swipeRow, rowParams);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(24), dp(4), dp(10), 0);
        TextView title = text("历史记录  ·  最近100条", 22, Color.rgb(48, 52, 56));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView close = text("×", 34, Color.rgb(91, 99, 105));
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.rgb(244, 246, 247), 24));
        close.setContentDescription("关闭");
        close.setClickable(true);
        titleBar.addView(close, new LinearLayout.LayoutParams(dp(58), dp(52)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(titleBar)
                .setView(scroll)
                .setNeutralButton("手动补录", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        editRecord(null);
                    }
                })
                .create();
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(dp(1040), dp(650));
    }

    private void beginInlineRecordEdit(final ScaleDatabase.Record record,
                                       final TextView number,
                                       final SwipeRecordRow swipeRow) {
        final LinearLayout row = (LinearLayout) number.getParent();
        if (row == null) return;
        final int index = row.indexOfChild(number);
        final EditText editor = new EditText(this);
        editor.setText(String.format(Locale.CHINA, "%.2f", record.displayWeight));
        editor.setTextSize(23);
        editor.setTextColor(Color.rgb(0, 174, 120));
        editor.setTypeface(Typeface.DEFAULT_BOLD);
        editor.setSingleLine(true);
        editor.setSelectAllOnFocus(true);
        editor.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(175),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.removeViewAt(index);
        row.addView(editor, index, params);
        swipeRow.close();
        final boolean[] finished = new boolean[]{false};
        final Runnable save = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                finished[0] = true;
                float displayWeight;
                try {
                    displayWeight = Float.parseFloat(editor.getText().toString().trim());
                    if (displayWeight <= 0) throw new IllegalArgumentException();
                    float kg = "斤".equals(record.unit) ? displayWeight / 2f
                            : "lb".equals(record.unit) ? displayWeight * 0.45359237f
                            : displayWeight;
                    database.updateRecord(record.id, record.userId, record.measuredAt,
                            kg, record.unit);
                    number.setText(String.format(Locale.CHINA, "%.2f %s",
                            displayWeight, record.unit));
                    Toast.makeText(MainActivity.this, "体重已修改",
                            Toast.LENGTH_SHORT).show();
                    refreshDashboardChart();
                } catch (RuntimeException error) {
                    Toast.makeText(MainActivity.this, "请输入有效体重",
                            Toast.LENGTH_LONG).show();
                }
                row.removeView(editor);
                row.addView(number, index, params);
                InputMethodManager keyboard = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            }
        };
        editor.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                save.run();
                return true;
            }
            return false;
        });
        editor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) save.run();
            }
        });
        editor.requestFocus();
        editor.postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager keyboard = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    private void editRecord(final ScaleDatabase.Record record) {
        final List<ScaleDatabase.User> users = database.users();
        if (users.isEmpty()) {
            Toast.makeText(this, "请先添加家庭成员", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(26), dp(10), dp(26), 0);
        form.setFocusableInTouchMode(true);
        form.requestFocus();
        final Spinner userSpinner = new Spinner(this);
        String[] names = new String[users.size()];
        int userSelection = 0;
        for (int i = 0; i < users.size(); i++) {
            names[i] = users.get(i).name;
            if (record != null && users.get(i).id == record.userId) userSelection = i;
        }
        userSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        userSpinner.setSelection(userSelection);
        form.addView(userSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        final Spinner unitSpinner = new Spinner(this);
        final String[] units = new String[]{"kg", "斤", "lb"};
        unitSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, units));
        if (record != null) unitSpinner.setSelection("斤".equals(record.unit) ? 1
                : ("lb".equals(record.unit) ? 2 : 0));
        form.addView(unitSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        final EditText weight = new EditText(this);
        weight.setHint("体重数值");
        weight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (record != null) weight.setText(String.format(Locale.US, "%.2f", record.displayWeight));
        form.addView(weight, matchWidthWrap());
        final EditText time = new EditText(this);
        time.setHint("yyyy-MM-dd HH:mm");
        time.setSingleLine(true);
        time.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(record == null ? System.currentTimeMillis() : record.measuredAt)));
        form.addView(time, matchWidthWrap());

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(form)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            float shown = Float.parseFloat(weight.getText().toString().trim());
                            String unit = units[unitSpinner.getSelectedItemPosition()];
                            float kg = "斤".equals(unit) ? shown / 2f
                                    : "lb".equals(unit) ? shown * 0.45359237f : shown;
                            long measuredAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                                    .parse(time.getText().toString().trim()).getTime();
                            long userId = users.get(userSpinner.getSelectedItemPosition()).id;
                            if (record == null) database.addManualRecord(userId, measuredAt, kg, unit);
                            else database.updateRecord(record.id, userId, measuredAt, kg, unit);
                            updateHistoryButton();
                            restoreLatestMeasurement();
                            Toast.makeText(MainActivity.this, "体重记录已保存",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception error) {
                            Toast.makeText(MainActivity.this, "请检查体重和时间格式",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        if (record != null) {
            builder.setNeutralButton("删除", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    database.deleteRecord(record.id);
                    updateHistoryButton();
                    restoreLatestMeasurement();
                    Toast.makeText(MainActivity.this, "记录已删除", Toast.LENGTH_SHORT).show();
                }
            });
        }
        showDialogWithClose(builder, record == null ? "手动补录体重" : "体重记录详情");
    }

    private void showUserManager() {
        final List<ScaleDatabase.User> users = database.users();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(10), dp(20), dp(14));
        if (users.isEmpty()) {
            TextView empty = text("尚未添加家庭成员\n添加后可按体重区间自动识别",
                    18, Color.rgb(132, 141, 148));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, dp(36));
            list.addView(empty, matchWidthWrap());
        }
        for (final ScaleDatabase.User user : users) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(9), dp(16), dp(9));
            row.setBackground(rounded(Color.rgb(247, 249, 249), 10));
            TextView avatar = text(user.name.length() == 0 ? "人" : user.name.substring(0, 1),
                    20, Color.rgb(0, 158, 109));
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(rounded(Color.rgb(225, 247, 239), 24));
            row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView info = text(user.name + "\n" + String.format(Locale.CHINA,
                    "自动识别 %.1f–%.1f kg · %s · %.0f cm · 目标 %.1f kg",
                    user.minKg, user.maxKg, user.gender, user.heightCm, user.targetKg),
                    17, Color.rgb(48, 52, 56));
            info.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoParams.setMargins(dp(16), 0, 0, 0);
            row.addView(info, infoParams);
            TextView arrow = text("›", 32, Color.rgb(170, 176, 180));
            row.addView(arrow, wrap());
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { editUser(user); }
            });
            LinearLayout.LayoutParams rowParams = matchWidthWrap();
            rowParams.setMargins(0, 0, 0, dp(8));
            list.addView(row, rowParams);
        }
        Button add = actionButton("＋ 添加家庭成员", false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editUser(null); }
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        addParams.setMargins(0, dp(8), 0, 0);
        list.addView(add, addParams);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = showDialogWithClose(new AlertDialog.Builder(this).setView(scroll),
                "家庭成员与自动识别区间");
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(dp(760), dp(620));
    }

    private void editUser(final ScaleDatabase.User user) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(26), dp(12), dp(26), 0);
        form.setFocusableInTouchMode(true);
        form.requestFocus();
        final EditText name = new EditText(this);
        name.setHint("例如：陈先生");
        name.setSingleLine(true);
        if (user != null) name.setText(user.name);
        form.addView(labeledFormRow("姓名", name), matchWidthWrap());
        final Spinner gender = new Spinner(this);
        String[] genders = new String[]{"未设置", "男", "女"};
        gender.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, genders));
        if (user != null) gender.setSelection("男".equals(user.gender) ? 1
                : ("女".equals(user.gender) ? 2 : 0));
        form.addView(labeledFormRow("性别", gender), matchWidthWrap());
        final EditText birth = new EditText(this);
        birth.setHint("例如 1990（可选）");
        birth.setSingleLine(true);
        birth.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (user != null && user.birthYear > 0) birth.setText(Integer.toString(user.birthYear));
        form.addView(labeledFormRow("出生年份", birth), matchWidthWrap());
        final EditText height = new EditText(this);
        height.setHint("例如 175（用于计算 BMI）");
        height.setSingleLine(true);
        height.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (user != null && user.heightCm > 0)
            height.setText(String.format(Locale.US, "%.1f", user.heightCm));
        form.addView(labeledFormRow("身高（cm）", height), matchWidthWrap());
        final EditText target = new EditText(this);
        target.setHint("例如 65（可选）");
        target.setSingleLine(true);
        target.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (user != null && user.targetKg > 0)
            target.setText(String.format(Locale.US, "%.1f", user.targetKg));
        form.addView(labeledFormRow("目标体重（kg）", target), matchWidthWrap());
        final EditText min = new EditText(this);
        min.setHint("例如 55");
        min.setSingleLine(true);
        min.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (user != null) min.setText(String.format(Locale.US, "%.1f", user.minKg));
        form.addView(labeledFormRow("最低体重（kg）", min), matchWidthWrap());
        final EditText max = new EditText(this);
        max.setHint("例如 80");
        max.setSingleLine(true);
        max.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (user != null) max.setText(String.format(Locale.US, "%.1f", user.maxKg));
        form.addView(labeledFormRow("最高体重（kg）", max), matchWidthWrap());

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(form)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String userName = name.getText().toString().trim();
                        try {
                            float minKg = Float.parseFloat(min.getText().toString().trim());
                            float maxKg = Float.parseFloat(max.getText().toString().trim());
                            int birthYear = TextUtils.isEmpty(birth.getText()) ? 0
                                    : Integer.parseInt(birth.getText().toString().trim());
                            float heightCm = TextUtils.isEmpty(height.getText()) ? 0
                                    : Float.parseFloat(height.getText().toString().trim());
                            float targetKg = TextUtils.isEmpty(target.getText()) ? 0
                                    : Float.parseFloat(target.getText().toString().trim());
                            String genderValue = gender.getSelectedItemPosition() == 1 ? "男"
                                    : gender.getSelectedItemPosition() == 2 ? "女" : "未设置";
                            if (TextUtils.isEmpty(userName) || minKg <= 0 || maxKg <= minKg) {
                                throw new IllegalArgumentException();
                            }
                            if (user == null) database.addUser(userName, minKg, maxKg,
                                    genderValue, birthYear, heightCm, targetKg);
                            else database.updateUser(user.id, userName, minKg, maxKg,
                                    genderValue, birthYear, heightCm, targetKg);
                            updateHistoryButton();
                            renderMemberStrip();
                            refreshDashboardChart();
                            Toast.makeText(MainActivity.this, "用户已保存，历史记录已重新识别",
                                    Toast.LENGTH_LONG).show();
                        } catch (RuntimeException error) {
                            Toast.makeText(MainActivity.this, "请输入有效姓名和体重区间",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
        if (user != null) {
            builder.setNeutralButton("删除用户", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    database.deleteUser(user.id);
                    updateHistoryButton();
                    renderMemberStrip();
                    refreshDashboardChart();
                    Toast.makeText(MainActivity.this, "用户已删除，记录已重新识别",
                            Toast.LENGTH_LONG).show();
                }
            });
        }
        showDialogWithClose(builder, user == null ? "新增用户" : "编辑用户");
    }

    private void showTrendUserPicker() {
        final List<ScaleDatabase.User> users = database.users();
        if (users.isEmpty()) {
            showTrendRangePicker(-1L, "全部记录");
            return;
        }
        String[] items = new String[users.size() + 1];
        for (int i = 0; i < users.size(); i++) items[i] = users.get(i).name;
        items[users.size()] = "全部记录";
        AlertDialog.Builder userPicker = new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which < users.size()) {
                            ScaleDatabase.User selected = users.get(which);
                            showTrendRangePicker(selected.id, selected.name);
                        } else {
                            showTrendRangePicker(-1L, "全部记录");
                        }
                    }
                });
        showDialogWithClose(userPicker, "选择用户");
    }

    private void showTrendRangePicker(final long userId, final String userName) {
        final String[] ranges = new String[]{"最近7天", "最近30天", "长期趋势（全部）"};
        AlertDialog.Builder rangePicker = new AlertDialog.Builder(this)
                .setItems(ranges, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showTrend(userId, userName, which == 0 ? 7 : (which == 1 ? 30 : 0));
                    }
                });
        showDialogWithClose(rangePicker, userName + " · 选择趋势范围");
    }

    private void showTrend(long userId, String userName, int days) {
        long since = days == 0 ? 0L : System.currentTimeMillis() -
                days * 24L * 60L * 60L * 1000L;
        List<ScaleDatabase.TrendPoint> points = database.trend(userId, since);
        String unit = points.isEmpty() ? "kg" : points.get(points.size() - 1).unit;
        Float change = database.latestChangeKg(userId);
        String rangeName = days == 0 ? "长期趋势" : days + "天趋势";

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(10));
        TextView summary = text("记录 " + points.size() + " 条" +
                        (change == null ? "" : "    " + ScaleDatabase.formatChange(change, unit)),
                18, Color.rgb(0, 158, 109));
        body.addView(summary, matchWidthWrap());
        TrendChartView chart = new TrendChartView(this, points, unit);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(400));
        chartParams.setMargins(0, dp(8), 0, 0);
        body.addView(chart, chartParams);
        AlertDialog dialog = showDialogWithClose(new AlertDialog.Builder(this).setView(body),
                userName + " · " + rangeName);
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(dp(980), dp(610));
    }

    @Override public void onGattStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                hintView.setText(message);
                gattStatusView.setText("历史同步：" + message);
                if (syncButton != null) syncButton.setText("同步进行中…");
            }
        });
    }

    @Override public void onHistoryRecord(final MiScaleParser.Result result) {
        database.insert(lastAddress, result, "history");
        runOnUiThread(new Runnable() {
            @Override public void run() { updateHistoryButton(); }
        });
    }

    @Override public void onGattComplete(final int expected, final int received,
                                         final boolean timeSynced, final boolean initialized) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                updateHistoryButton();
                StringBuilder message = new StringBuilder("秤内历史同步完成，接收 ")
                        .append(received).append(" 条");
                if (timeSynced) message.append("；秤内时间已校准");
                if (initialized) message.append("；历史功能已初始化");
                hintView.setText(message.toString());
                gattStatusView.setText("历史同步：" + message.toString());
                Toast.makeText(MainActivity.this, message.toString(), Toast.LENGTH_LONG).show();
                syncButton.setEnabled(true);
                syncButton.setText("同步秤内历史");
            }
        });
    }

    @Override public void onGattClosed() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (syncButton != null) {
                    syncButton.setEnabled(true);
                    syncButton.setText("同步秤内历史");
                }
                if (foreground) startScan();
            }
        });
    }

    private void setProblem(String message) {
        scanning = false;
        stateView.setText(message);
        stateView.setTextColor(Color.WHITE);
        hintView.setText("解决后点击“重新扫描”");
        if (scanButton != null) scanButton.setText("重新扫描");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureReadyAndScan();
            } else {
                setProblem("位置权限被拒绝，无法扫描蓝牙体重秤");
            }
        }
    }

    private void renderMemberStrip() {
        if (memberStrip == null) return;
        memberStrip.removeAllViews();
        TextView label = text("家庭成员", 18, Color.rgb(48, 52, 56));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams labelParams = wrap();
        labelParams.setMargins(0, 0, dp(18), 0);
        memberStrip.addView(label, labelParams);
        List<ScaleDatabase.User> users = database == null
                ? new java.util.ArrayList<ScaleDatabase.User>() : database.users();
        if (users.isEmpty()) {
            TextView empty = text("尚未添加成员，体重记录将暂存为“未识别”",
                    15, Color.rgb(132, 141, 148));
            memberStrip.addView(empty, wrap());
        }
        for (final ScaleDatabase.User user : users) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(4), 0, dp(13), 0);
            chip.setBackground(rounded(Color.rgb(244, 246, 247), 24));
            String initial = user.name.length() > 0 ? user.name.substring(0, 1) : "人";
            TextView avatar = text(initial, 17, Color.WHITE);
            avatar.setGravity(Gravity.CENTER);
            avatar.setTypeface(Typeface.DEFAULT_BOLD);
            avatar.setBackground(rounded(Color.rgb(0, 190, 132), 22));
            chip.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView name = text(user.name, 16, Color.rgb(54, 61, 66));
            LinearLayout.LayoutParams nameParams = wrap();
            nameParams.setMargins(dp(9), 0, 0, 0);
            chip.addView(name, nameParams);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectMember(user);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            params.setMargins(0, 0, dp(10), 0);
            memberStrip.addView(chip, params);
        }
        memberStrip.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        usersButton = actionButton(users.isEmpty() ? "＋ 添加成员" : "管理成员", false);
        usersButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showUserManager(); }
        });
        memberStrip.addView(usersButton, new LinearLayout.LayoutParams(dp(138), dp(48)));
    }

    private void refreshDashboardChart() {
        if (dashboardChartHolder == null || database == null) return;
        dashboardChartHolder.removeAllViews();
        long since = dashboardTrendDays == 0 ? 0L : System.currentTimeMillis()
                - dashboardTrendDays * 24L * 60L * 60L * 1000L;
        List<ScaleDatabase.TrendPoint> points = database.trend(currentUserId, since);
        String unit = points.isEmpty() ? "kg" : points.get(points.size() - 1).unit;
        TrendChartView chart = new TrendChartView(this, points, unit);
        dashboardChartHolder.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void selectMember(ScaleDatabase.User user) {
        manualMemberSelection = true;
        measurementSessionActive = false;
        currentUserId = user.id;
        currentUserView.setText(user.name);
        List<ScaleDatabase.Record> records = database.latestForUser(user.id, 1);
        if (records.isEmpty()) {
            activeWeightKg = 0;
            weightView.setText("--.--");
            unitView.setText("kg");
            updatedView.setText("暂无体重记录");
            changeView.setText("首次记录");
            changeView.setTextColor(Color.rgb(0, 185, 120));
            stateView.setText("等待测量");
            hintView.setText("请踩上体重秤开始测量");
            updateHealthStatus(user, 0);
        } else {
            ScaleDatabase.Record record = records.get(0);
            activeWeightKg = record.weightKg;
            weightView.setText(String.format(Locale.CHINA, "%.2f", record.displayWeight));
            unitView.setText(record.unit);
            updatedView.setText(new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                    .format(new Date(record.measuredAt)));
            if (record.changeKg == null) {
                changeView.setText("首次记录");
                changeView.setTextColor(Color.rgb(0, 185, 120));
            } else {
                float displayChange = ScaleDatabase.convertFromKg(record.changeKg, record.unit);
                changeView.setText(String.format(Locale.CHINA, "%+.2f %s",
                        displayChange, record.unit));
                changeView.setTextColor(displayChange > 0
                        ? Color.rgb(255, 91, 80) : Color.rgb(0, 185, 120));
            }
            stateView.setText("上次稳定记录");
            hintView.setText("请踩上体重秤开始新测量");
            updateHealthStatus(user, record.weightKg);
        }
        refreshDashboardChart();
    }

    private void restoreLatestMeasurement() {
        if (database == null) return;
        List<ScaleDatabase.Record> records = database.latest(1);
        if (records.isEmpty()) return;
        ScaleDatabase.Record record = records.get(0);
        weightView.setText(String.format(Locale.CHINA, "%.2f", record.displayWeight));
        unitView.setText(record.unit);
        updatedView.setText(new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                .format(new Date(record.measuredAt)));
        ScaleDatabase.User matched = database.matchedUser(record.weightKg);
        // A record's stored user name reflects the ranges that existed when it was saved.
        // On startup, always re-evaluate the weight against the current member ranges so
        // edited ranges cannot leave a stale member name on the dashboard.
        currentUserView.setText(matched == null ? "未识别" : matched.name);
        currentUserId = matched == null ? -1L : matched.id;
        activeWeightKg = record.weightKg;
        updateHealthStatus(matched, record.weightKg);
        if (record.changeKg == null) {
            changeView.setText("首次记录");
            changeView.setTextColor(Color.rgb(0, 185, 120));
        } else {
            float displayChange = ScaleDatabase.convertFromKg(record.changeKg, record.unit);
            changeView.setText(String.format(Locale.CHINA, "%+.2f %s", displayChange, record.unit));
            changeView.setTextColor(displayChange > 0
                    ? Color.rgb(255, 91, 80) : Color.rgb(0, 185, 120));
        }
        stateView.setText("上次稳定记录");
        hintView.setText("请踩上体重秤开始新测量");
        refreshDashboardChart();
    }

    private void updateHealthStatus(ScaleDatabase.User user, float weightKg) {
        if (bmiView == null) return;
        if (user == null) {
            bmiView.setText("BMI -- · 未识别成员");
            targetView.setText("请先设置成员资料和识别区间");
            bmiBandView.setBmi(0);
            return;
        }
        targetView.setText(user.targetKg > 0
                ? String.format(Locale.CHINA, "目标体重 %.1f kg · 相差 %+.1f kg",
                        user.targetKg, weightKg - user.targetKg)
                : "目标体重：未设置");
        if (user.heightCm <= 0 || weightKg <= 0) {
            bmiView.setText("BMI -- · 请完善成员身高");
            bmiBandView.setBmi(0);
            return;
        }
        float heightM = user.heightCm / 100f;
        float bmi = weightKg / (heightM * heightM);
        String status = bmi < 18.5f ? "偏轻" : bmi < 24f ? "标准" : bmi < 28f ? "偏重" : "肥胖";
        bmiView.setText(String.format(Locale.CHINA, "BMI %.1f · %s", bmi, status));
        bmiBandView.setBmi(bmi);
    }

    private LinearLayout metricBox(String label, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView valueView = text(value, 25, color);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setGravity(Gravity.CENTER);
        box.addView(valueView, matchWidthWrap());
        TextView labelView = text(label, 15, Color.rgb(132, 141, 148));
        labelView.setGravity(Gravity.CENTER);
        box.addView(labelView, matchWidthWrap());
        return box;
    }

    private LinearLayout labeledFormRow(String label, View field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 16, Color.rgb(54, 61, 66));
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(145), dp(48)));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        row.addView(field, fieldParams);
        return row;
    }

    private Button addAction(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = actionButton(label, false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(button, params);
        return button;
    }

    private Button actionButton(String label, boolean onGreen) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        if (onGreen) {
            button.setTextColor(Color.WHITE);
            button.setBackground(rounded(Color.argb(45, 255, 255, 255), 22));
        } else {
            button.setTextColor(Color.rgb(0, 158, 109));
            button.setBackground(rounded(Color.WHITE, 12));
            button.setElevation(dp(1));
        }
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void showDeviceInfo() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(26), dp(14), dp(26), dp(12));
        TextView info = text(deviceView.getText().toString(), 18, Color.rgb(48, 52, 56));
        info.setLineSpacing(dp(5), 1f);
        body.addView(info, matchWidthWrap());
        TextView sync = text(gattStatusView.getText().toString(), 16,
                Color.rgb(132, 141, 148));
        LinearLayout.LayoutParams syncParams = matchWidthWrap();
        syncParams.setMargins(0, dp(16), 0, 0);
        body.addView(sync, syncParams);
        final Switch mergeSwitch = new Switch(this);
        mergeSwitch.setText("合并同一成员 30 秒内的重复称重");
        mergeSwitch.setTextSize(17);
        mergeSwitch.setChecked(preferences.getBoolean("merge_30_seconds", true));
        mergeSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean("merge_30_seconds", isChecked).apply());
        LinearLayout.LayoutParams mergeParams = matchWidthWrap();
        mergeParams.setMargins(0, dp(15), 0, 0);
        body.addView(mergeSwitch, mergeParams);
        String bound = preferences.getString("bound_device", "");
        Button bindButton = actionButton(TextUtils.isEmpty(bound)
                ? "选择并绑定附近体重秤" : "已绑定 " + bound + "（点击更换）", false);
        bindButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDevicePicker(); }
        });
        LinearLayout.LayoutParams bindParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        bindParams.setMargins(0, dp(12), 0, 0);
        body.addView(bindButton, bindParams);
        TextView rawTitle = text("原始广播数据", 15, Color.rgb(132, 141, 148));
        LinearLayout.LayoutParams rawTitleParams = matchWidthWrap();
        rawTitleParams.setMargins(0, dp(18), 0, dp(5));
        body.addView(rawTitle, rawTitleParams);
        TextView raw = text(packetView.getText().toString(), 16, Color.rgb(48, 52, 56));
        raw.setTypeface(Typeface.MONOSPACE);
        raw.setTextIsSelectable(true);
        body.addView(raw, matchWidthWrap());
        AlertDialog.Builder deviceInfoBuilder = new AlertDialog.Builder(this)
                .setView(body)
                .setNeutralButton("重新扫描", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        stopScan();
                        ensureReadyAndScan();
                    }
                });
        showDialogWithClose(deviceInfoBuilder, "设备信息");
    }

    private void showDevicePicker() {
        final ArrayList<String> addresses = new ArrayList<String>(nearbyDevices.keySet());
        final String[] items = new String[addresses.size() + 1];
        items[0] = "不绑定（接收附近任意兼容体重秤）";
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            BluetoothDevice device = nearbyDevices.get(address);
            String name = device == null ? "小米体重秤" : device.getName();
            if (TextUtils.isEmpty(name)) name = "小米体重秤";
            Integer rssi = nearbyRssi.get(address);
            items[i + 1] = name + "  " + address + (rssi == null ? "" : "  " + rssi + " dBm");
        }
        AlertDialog.Builder devicePicker = new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String address = which == 0 ? "" : addresses.get(which - 1);
                        preferences.edit().putString("bound_device", address).apply();
                        if (which > 0) {
                            lastDevice = nearbyDevices.get(address);
                            lastAddress = address;
                        }
                        Toast.makeText(MainActivity.this, which == 0 ? "已取消设备绑定"
                                : "已绑定 " + address, Toast.LENGTH_LONG).show();
                    }
                });
        showDialogWithClose(devicePicker,
                addresses.isEmpty() ? "尚未发现体重秤" : "选择要绑定的体重秤");
    }

    private AlertDialog showDialogWithClose(AlertDialog.Builder builder, String titleText) {
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(24), dp(4), dp(10), 0);
        TextView title = text(titleText, 22, Color.rgb(48, 52, 56));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView close = text("×", 34, Color.rgb(91, 99, 105));
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭");
        close.setClickable(true);
        close.setBackground(rounded(Color.rgb(244, 246, 247), 24));
        titleBar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        builder.setCustomTitle(titleBar);
        final AlertDialog dialog = builder.create();
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        dialog.show();
        return dialog;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(rounded(Color.WHITE, 14));
        layout.setElevation(dp(2));
        return layout;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
