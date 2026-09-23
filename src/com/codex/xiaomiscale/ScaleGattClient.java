package com.codex.xiaomiscale;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Calendar;
import java.util.UUID;

public final class ScaleGattClient {
    private static final UUID SERVICE_WEIGHT = uuid16("181d");
    private static final UUID CHAR_TIME = uuid16("2a2b");
    private static final UUID CHAR_HISTORY = UUID.fromString(
            "00002a2f-0000-3512-2118-0009af100700");
    private static final UUID CCCD = uuid16("2902");
    private static final int STATE_NONE = 0;
    private static final int STATE_TIME = 1;
    private static final int STATE_QUERY = 2;
    private static final int STATE_REQUEST = 3;
    private static final int STATE_STOP = 4;
    private static final int STATE_ACK = 5;
    private static final int STATE_INIT = 6;

    public interface Listener {
        void onGattStatus(String message);
        void onHistoryRecord(MiScaleParser.Result result);
        void onGattComplete(int expected, int received, boolean timeSynced, boolean initialized);
        void onGattClosed();
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic timeChar;
    private BluetoothGattCharacteristic historyChar;
    private boolean shouldSyncTime;
    private boolean timeSynced;
    private boolean initialized;
    private boolean receivedNotification;
    private boolean finished;
    private int expected = -1;
    private int received;
    private int state = STATE_NONE;

    public ScaleGattClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start(BluetoothDevice device, boolean syncTime) {
        close();
        shouldSyncTime = syncTime;
        timeSynced = false;
        initialized = false;
        receivedNotification = false;
        finished = false;
        expected = -1;
        received = 0;
        status("正在连接体重秤…");
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    public void close() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) {}
            try { gatt.close(); } catch (RuntimeException ignored) {}
        }
        gatt = null;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int statusCode,
                                                      int newState) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED) {
                status("已连接，正在发现服务…");
                current.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!finished) status("体重秤连接已断开");
                closeGattOnly(current);
                listener.onGattClosed();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败：" + statusCode);
                return;
            }
            BluetoothGattService service = current.getService(SERVICE_WEIGHT);
            if (service == null) {
                fail("没有找到体重秤历史服务 0x181D");
                return;
            }
            timeChar = service.getCharacteristic(CHAR_TIME);
            historyChar = service.getCharacteristic(CHAR_HISTORY);
            if (historyChar == null) {
                fail("没有找到历史记录特征 0x2A2F");
                return;
            }
            BluetoothGattDescriptor descriptor = historyChar.getDescriptor(CCCD);
            if (descriptor == null || !current.setCharacteristicNotification(historyChar, true)) {
                fail("无法开启历史记录通知");
                return;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            status("正在开启秤内历史通道…");
            if (!current.writeDescriptor(descriptor)) fail("历史通知配置写入失败");
        }

        @Override public void onDescriptorWrite(BluetoothGatt current,
                                                BluetoothGattDescriptor descriptor, int code) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                fail("历史通知配置失败：" + code);
                return;
            }
            if (shouldSyncTime && timeChar != null) {
                writeTime();
            } else {
                queryHistory();
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt current,
                                                    BluetoothGattCharacteristic characteristic,
                                                    int code) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                if (!initialized && (state == STATE_QUERY || state == STATE_REQUEST)) {
                    initializeAndRetry();
                } else {
                    fail("体重秤写入失败：" + code);
                }
                return;
            }
            if (state == STATE_TIME) {
                timeSynced = true;
                status("时间已校准，正在读取秤内历史…");
                handler.postDelayed(new Runnable() {
                    @Override public void run() { queryHistory(); }
                }, 350);
            } else if (state == STATE_QUERY) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { requestHistory(); }
                }, 500);
            } else if (state == STATE_INIT) {
                status("历史功能已重新初始化，正在重试…");
                handler.postDelayed(new Runnable() {
                    @Override public void run() { queryHistory(); }
                }, 600);
            } else if (state == STATE_STOP) {
                writeHistory(new byte[]{0x04, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff}, STATE_ACK);
            } else if (state == STATE_ACK) {
                complete();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current,
                                                      BluetoothGattCharacteristic characteristic) {
            if (!CHAR_HISTORY.equals(characteristic.getUuid())) return;
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;
            receivedNotification = true;
            handler.removeCallbacks(timeout);
            int command = data[0] & 0xff;
            if (command == 0x01 && data.length >= 2) {
                expected = data[1] & 0xff;
                if (data.length >= 3 && (data[2] & 0xff) != 0xff) {
                    expected |= (data[2] & 0xff) << 8;
                }
                status("秤内待同步记录：" + expected + " 条");
                return;
            }
            if (command == 0x03 && data.length <= 6) {
                status("历史数据接收完成，正在确认…");
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        writeHistory(new byte[]{0x03}, STATE_STOP);
                    }
                }, 250);
                return;
            }
            int offset = data.length % 10 == 0 ? 0 :
                    (command == 0x02 && (data.length - 1) % 10 == 0 ? 1 : -1);
            if (offset >= 0) {
                while (offset + 10 <= data.length) {
                    byte[] packet = new byte[10];
                    System.arraycopy(data, offset, packet, 0, 10);
                    MiScaleParser.Result parsed = MiScaleParser.parse(packet);
                    if (parsed != null && parsed.stable && !parsed.removed &&
                            parsed.measurementTimeMillis > 0) {
                        received++;
                        listener.onHistoryRecord(parsed);
                    }
                    offset += 10;
                }
                status("正在接收历史记录：" + received +
                        (expected >= 0 ? "/" + expected : ""));
            }
            handler.postDelayed(timeout, 8000);
        }
    };

    private void writeTime() {
        if (timeChar == null) {
            queryHistory();
            return;
        }
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        byte[] value = new byte[]{
                (byte) (year & 0xff), (byte) ((year >> 8) & 0xff),
                (byte) (now.get(Calendar.MONTH) + 1),
                (byte) now.get(Calendar.DAY_OF_MONTH),
                (byte) now.get(Calendar.HOUR_OF_DAY),
                (byte) now.get(Calendar.MINUTE),
                (byte) now.get(Calendar.SECOND), 0x03, 0x00, 0x00};
        timeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        timeChar.setValue(value);
        state = STATE_TIME;
        status("CC10时间快至少1小时，正在校准秤内时间…");
        if (!gatt.writeCharacteristic(timeChar)) fail("无法写入秤内时间");
    }

    private void queryHistory() {
        receivedNotification = false;
        status("正在查询秤内历史记录…");
        writeHistory(new byte[]{0x01, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff}, STATE_QUERY);
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 5000);
    }

    private void requestHistory() {
        writeHistory(new byte[]{0x02}, STATE_REQUEST);
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 10000);
    }

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (finished) return;
            if (!receivedNotification && !initialized) {
                initializeAndRetry();
            } else {
                status("历史读取等待结束，已收到 " + received + " 条");
                writeHistory(new byte[]{0x03}, STATE_STOP);
            }
        }
    };

    private void initializeAndRetry() {
        initialized = true;
        status("历史通道无响应，正在执行掉电恢复初始化…");
        writeHistory(new byte[]{0x01, (byte) 0x96, (byte) 0x8a,
                (byte) 0xbd, 0x62}, STATE_INIT);
    }

    private void writeHistory(byte[] value, int nextState) {
        if (gatt == null || historyChar == null) {
            fail("历史通道不可用");
            return;
        }
        state = nextState;
        historyChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        historyChar.setValue(value);
        if (!gatt.writeCharacteristic(historyChar)) fail("历史命令发送失败");
    }

    private void complete() {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        listener.onGattComplete(expected, received, timeSynced, initialized);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) {}
        }
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        status(message);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) {}
        } else {
            listener.onGattClosed();
        }
    }

    private void status(final String message) {
        Log.i("XiaomiScaleGatt", message);
        handler.post(new Runnable() {
            @Override public void run() { listener.onGattStatus(message); }
        });
    }

    private void closeGattOnly(BluetoothGatt current) {
        try { current.close(); } catch (RuntimeException ignored) {}
        if (gatt == current) gatt = null;
    }

    private static UUID uuid16(String shortUuid) {
        return UUID.fromString("0000" + shortUuid + "-0000-1000-8000-00805f9b34fb");
    }
}
