package com.wongfei.sensorbroadcaster;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SensorBroadcasterService extends Service implements Runnable, SensorEventListener {

    private class SensorContext {
        public Sensor sensor;
        public int uid;
        public int rate;
        public boolean enabled;

        public SensorContext(Sensor sensor, int uid) {
            this.sensor = sensor;
            this.uid = uid;
            this.rate = SensorManager.SENSOR_DELAY_NORMAL;
            this.enabled = false;
        }
    }

    private final static String TAG = "SensorBroadcasterSrv";
    private final static String NOTIF_CHANNEL_ID = "SensorBroadcasterSrv";
    private final static int NOTIF_ID = 1;

    private final static int PK_REQ_DETECT_DEVICE = 0xA0;
    private final static int PK_RESP_DETECT_DEVICE = 0xA1;
    private final static int PK_REQ_PING_DEVICE = 0xA2;
    private final static int PK_RESP_PING_DEVICE = 0xA3;
    private final static int PK_REQ_ENUMERATE_SENSORS = 0xB0;
    private final static int PK_RESP_ENUMERATE_SENSORS = 0xB1;
    private final static int PK_REQ_ENABLE_SENSOR = 0xB2;
    private final static int PK_RESP_ENABLE_SENSOR = 0xB3;
    private final static int PK_REQ_DISABLE_ALL_SENSORS = 0xB4;
    private final static int PK_RESP_DISABLE_ALL_SENSORS = 0xB5;
    private final static int PK_CB_SENSOR_EVENT = 0xC0;

    private static SensorBroadcasterService instance = null;

    private int port = 0;
    private String password = "";

    private long workerTickRate = 1000 / 60;
    private long workerSleepTimeout = 500;
    private long broadcastHelloRate = 1000;

    private SensorManager sensorManager = null;
    private ArrayList<SensorContext> sensors = new ArrayList<>();
    private ArrayList<SensorEvent> sensorEvents = new ArrayList<>();

    private Object triggerListener = null;
    private ArrayList<TriggerEvent> triggerEvents = new ArrayList<>();

    private DatagramChannel channel = null;
    private SocketAddress clientAddr = null;
    private ByteBuffer buffer = ByteBuffer.allocateDirect(2048);
    private int totalBytesSent = 0;
    private int totalPacketsSent = 0;

    private AtomicBoolean runFlag = new AtomicBoolean(false);
    private Thread worker = null;
    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;
    //private WifiManager.MulticastLock mcastLock = null;

    //==============================================================================
    // Service
    //==============================================================================

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        runFlag.set(false);
        if (worker != null) {
            try {
                worker.join();
            } catch (Exception ex) {
                // IGNORE
            }
            worker = null;
        }

        instance = null;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        Bundle extras = intent.getExtras();
        if (extras != null) {
            port = Integer.parseInt((String) extras.get("port"));
            password = (String) extras.get("password");
        }

        runFlag.set(true);
        worker = new Thread(this);
        worker.start();

        startForeground();
        return super.onStartCommand(intent, flags, startId);
    }

    private final void startForeground() {
        Log.d(TAG, "startForeground");

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        startForeground(NOTIF_ID, new NotificationCompat.Builder(this,
                NOTIF_CHANNEL_ID) // don't forget create a notification channel first
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Service is running background")
                .setContentIntent(pendingIntent)
                .build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return null;
    }

    //==============================================================================
    // Runnable
    //==============================================================================

    @Override
    public void run() {
        Log.d(TAG, "ENTER: run");
        try {
            initSensors();
            initSocket();
            acquireWakelock();

            Log.d(TAG, "MAIN LOOP");
            long lastBroadcasted = 0;

            while (runFlag.get()) {
                long t0 = SystemClock.elapsedRealtime();

                // send self discovery packet because on some hardware udp broadcasts not received when screen turned off
                if (lastBroadcasted + broadcastHelloRate < t0) {
                    //Log.d(TAG, "hello");
                    lastBroadcasted = t0;
                    broadcastHello();
                }

                try {
                    processRequests();
                    if (clientAddr != null) {
                        sendSensorEvents(clientAddr);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "EXCEPTION: run", ex);
                    disableAllSensors();
                    clientAddr = null;
                }

                long t1 = SystemClock.elapsedRealtime();
                long dt = t1 - t0;

                if (clientAddr != null) {
                    Thread.sleep(dt < workerTickRate ? workerTickRate - dt : 1);
                } else {
                    Thread.sleep(workerSleepTimeout);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "EXCEPTION: run", ex);
        }

        releaseWakelock();
        shutdownSensors();
        shutdownSocket();
        Log.d(TAG, "LEAVE: run");
    }

    private final void acquireWakelock() {
        Log.d(TAG, "acquireWakelock");

        int wakeType = PowerManager.PARTIAL_WAKE_LOCK;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(wakeType, TAG + "::wakeLock");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG + "::wifiLock");
        wifiLock.acquire();
    }

    private final void releaseWakelock() {
        Log.d(TAG, "releaseWakelock");

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    //==============================================================================
    // Socket
    //==============================================================================

    private final void initSocket() throws Exception {
        Log.d(TAG, "initSocket");
        Log.d(TAG, "endian: " + ByteOrder.nativeOrder());

        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.socket().setBroadcast(true);
        channel.socket().bind(new InetSocketAddress(port));

        totalBytesSent = 0;
        totalPacketsSent = 0;
    }

    private final void shutdownSocket() {
        if (channel != null) {
            Log.d(TAG, "shutdownSocket");
            try {
                channel.close();
            } catch (Exception ex) {
                // IGNORE
            }
            channel = null;
        }
        clientAddr = null;
    }

    private final void processRequests() throws Exception {
        for (; ; ) {
            buffer.clear();
            SocketAddress addr = channel.receive(buffer);
            if (addr == null || buffer.position() <= 0) {
                return;
            }

            buffer.flip();
            int id = readU8(buffer);
            //Log.d(TAG, "packet id=" + id + " len=" + buffer.position());

            switch (id) {
                case PK_REQ_DETECT_DEVICE:
                    reqDetectDevice(addr);
                    break;

                case PK_REQ_PING_DEVICE:
                    reqPingDevice(addr);
                    break;

                case PK_REQ_ENUMERATE_SENSORS:
                    reqEnumerateSensors(addr);
                    break;

                case PK_REQ_ENABLE_SENSOR:
                    reqEnableSensor(addr);
                    break;

                case PK_REQ_DISABLE_ALL_SENSORS:
                    reqDisableAllSensors(addr);
                    break;
            }
        }
    }

    private final void broadcastHello() throws IOException {
        initPacket(buffer, PK_RESP_DETECT_DEVICE);
        sendPacket(channel, buffer, new InetSocketAddress("255.255.255.255", port));
    }

    private final void reqDetectDevice(SocketAddress addr) throws Exception {
        Log.d(TAG, "reqDetectDevice");
        initPacket(buffer, PK_RESP_DETECT_DEVICE);
        sendPacket(channel, buffer, addr);
    }

    private final void reqPingDevice(SocketAddress addr) throws Exception {
        Log.d(TAG, "reqPingDevice");
        initPacket(buffer, PK_RESP_PING_DEVICE);
        sendPacket(channel, buffer, addr);
    }

    private final void reqEnumerateSensors(SocketAddress addr) throws Exception {
        Log.d(TAG, "reqEnumerateSensors");
        initPacket(buffer, PK_RESP_ENUMERATE_SENSORS);
        int n = writeU8(buffer, sensors.size());
        for (int i = 0; i < n; ++i) {
            SensorContext context = sensors.get(i);
            writeU8(buffer, context.uid);
            writeU8(buffer, context.sensor.getType());
            writeStringU8(buffer, context.sensor.getName());
        }
        sendPacket(channel, buffer, addr);
    }

    private final void reqEnableSensor(SocketAddress addr) throws Exception {
        Log.d(TAG, "reqEnableSensor");
        String clientPassword = readStringU8(buffer);
        int id = readU8(buffer);
        boolean enabled = readBool(buffer);
        int rate = readU8(buffer);
        boolean success = false;

        if (clientPassword.equals(password)) {
            success = enableSensor(id, enabled, rate);
            clientAddr = (success || haveEnabledSensors()) ? addr : null;
        }

        initPacket(buffer, PK_RESP_ENABLE_SENSOR);
        writeBool(buffer, success);
        writeU8(buffer, id);
        sendPacket(channel, buffer, addr);
    }

    private final void reqDisableAllSensors(SocketAddress addr) throws Exception {
        Log.d(TAG, "reqDisableAllSensors");
        String clientPassword = readStringU8(buffer);
        boolean success = false;

        if (clientPassword.equals(password)) {
            disableAllSensors();
            clientAddr = null;
            success = true;
        }

        initPacket(buffer, PK_RESP_DISABLE_ALL_SENSORS);
        writeBool(buffer, success);
        sendPacket(channel, buffer, addr);
    }

    private final void sendSensorEvents(SocketAddress addr) throws Exception {
        // GODLIKE java array swap!
        ArrayList<SensorEvent> tmpSensorEvents = null;
        if (sensorEvents.size() > 0) {
            synchronized (sensorEvents) {
                if (sensorEvents.size() > 0) {
                    tmpSensorEvents = sensorEvents;
                    sensorEvents = new ArrayList<>();
                }
            }
        }
        if (tmpSensorEvents != null) {
            for (SensorEvent event : tmpSensorEvents) {
                SensorContext context = findSensorContext(event.sensor);
                if (context != null && context.enabled) {
                    sendSensorEvent(addr, context, event.timestamp, event.values);
                }
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= 18) {
            ArrayList<TriggerEvent> tmpTriggerEvents = null;
            if (triggerEvents.size() > 0) {
                synchronized (triggerEvents) {
                    if (triggerEvents.size() > 0) {
                        tmpTriggerEvents = triggerEvents;
                        triggerEvents = new ArrayList<>();
                    }
                }
            }
            if (tmpTriggerEvents != null) {
                for (TriggerEvent event : tmpTriggerEvents) {
                    SensorContext context = findSensorContext(event.sensor);
                    if (context != null && context.enabled) {
                        sendSensorEvent(addr, context, event.timestamp, event.values);
                    }
                }
            }
        }
    }

    private final void sendSensorEvent(SocketAddress addr, SensorContext context, long timestamp, float[] values) throws Exception {
        initPacket(buffer, PK_CB_SENSOR_EVENT);
        writeU8(buffer, context.uid);
        buffer.putLong(timestamp);
        int n = writeU8(buffer, values.length);
        for (int i = 0; i < n; ++i) {
            buffer.putFloat(values[i]);
        }
        totalBytesSent += buffer.position();
        totalPacketsSent++;
        sendPacket(channel, buffer, addr);
    }

    //==============================================================================
    // Sensors
    //==============================================================================

    private final void initSensors() {
        Log.d(TAG, "initSensors");
        if (sensorManager != null || sensors.size() > 0) {
            shutdownSensors();
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL);
        int uid = 0;

        for (Sensor s : sensorList) {
            Log.d(TAG, s.toString());
            SensorContext context = new SensorContext(s, uid);
            sensors.add(context);
            uid++;
        }

        if (android.os.Build.VERSION.SDK_INT >= 18) {
            triggerListener = new TriggerEventListener() {
                @Override
                public void onTrigger(TriggerEvent event) {
                    synchronized (triggerEvents) {
                        triggerEvents.add(event);
                    }
                }
            };
        }
    }

    private final void shutdownSensors() {
        Log.d(TAG, "shutdownSensors");
        disableAllSensors();
        sensors.clear();
    }

    private final boolean enableSensor(int id, boolean enabled, int rate) {
        SensorContext context = findSensorContext(id);
        if (context == null) {
            Log.e(TAG, "enableSensor: not found: id=" + id);
            return false;
        }
        if (context.enabled != enabled) {
            if (context.enabled) {
                unregisterListener(context.sensor);
                context.enabled = false;
            } else {
                context.enabled = registerListener(context.sensor, rate);
            }
        }
        return context.enabled;
    }

    private final boolean registerListener(Sensor sensor, int rate) {
        Log.d(TAG, "registerListener sensor=" + sensor.getName() + " rate=" + rate);
        boolean success = false;

        // LOL android is so fucked up
        if (android.os.Build.VERSION.SDK_INT < 18) {
            success = sensorManager.registerListener(this, sensor, rate);
        } else if (android.os.Build.VERSION.SDK_INT < 21) {
            success = sensorManager.registerListener(this, sensor, rate);
            if (!success) {
                success = sensorManager.requestTriggerSensor((TriggerEventListener) triggerListener, sensor);
            }
        } else {
            if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                success = sensorManager.requestTriggerSensor((TriggerEventListener) triggerListener, sensor);
            } else {
                success = sensorManager.registerListener(this, sensor, rate);
            }
        }
        return success;
    }

    private final void unregisterListener(Sensor sensor) {
        Log.d(TAG, "unregisterListener sensor=" + sensor.getName());

        sensorManager.unregisterListener(this, sensor);

        if (triggerListener != null && android.os.Build.VERSION.SDK_INT >= 18) {
            sensorManager.cancelTriggerSensor((TriggerEventListener) triggerListener, sensor);
        }
    }

    private final void disableAllSensors() {
        Log.d(TAG, "disableAllSensors");
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Exception ex) {
                // IGNORE
            }
            if (triggerListener != null && android.os.Build.VERSION.SDK_INT >= 18) {
                sensorManager.cancelTriggerSensor((TriggerEventListener) triggerListener, null);
                triggerListener = null;
            }
        }
        for (SensorContext context : sensors) {
            context.enabled = false;
        }
        sensorEvents.clear();
        triggerEvents.clear();
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // EMPTY
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        synchronized (sensorEvents) {
            sensorEvents.add(event);
        }
    }

    private final SensorContext findSensorContext(Sensor sensor) {
        for (SensorContext iter : sensors) {
            if (iter.sensor == sensor) {
                return iter;
            }
        }
        return null;
    }

    private final SensorContext findSensorContext(int uid) {
        for (SensorContext iter : sensors) {
            if (iter.uid == uid) {
                return iter;
            }
        }
        return null;
    }

    private final boolean haveEnabledSensors() {
        for (SensorContext context : sensors) {
            if (context.enabled) {
                return true;
            }
        }
        return false;
    }

    //==============================================================================
    // Getters
    //==============================================================================

    public final static SensorBroadcasterService getInstance() {
        return instance;
    }

    public final SocketAddress getClientAddr() {
        return clientAddr;
    }

    public final int getTotalBytesSent() {
        return totalBytesSent;
    }

    public final int getTotalPacketsSent() {
        return totalPacketsSent;
    }

    //==============================================================================
    // Utils
    //==============================================================================

    private final static int castU8(byte x) {
        return ((int) x) & 0xff;
    }

    private final static void writeBool(ByteBuffer buf, boolean x) {
        buf.put((byte) (x ? 1 : 0));
    }

    private final static int writeU8(ByteBuffer buf, int x) {
        x = Math.min(x, 0xFF);
        buf.put((byte) x);
        return x;
    }

    private final static void writeArrayU8(ByteBuffer buf, byte[] data) {
        int n = writeU8(buf, data.length);
        for (int i = 0; i < n; ++i) {
            buf.put(data[i]);
        }
    }

    private final static void writeStringU8(ByteBuffer buf, String str) throws UnsupportedEncodingException {
        byte[] data = str.getBytes("UTF-8");
        writeArrayU8(buf, data);
    }

    private final static boolean readBool(ByteBuffer buf) {
        return buf.get() != 0;
    }

    private final static int readU8(ByteBuffer buf) {
        return castU8(buf.get());
    }

    private final static String readStringU8(ByteBuffer buf) throws UnsupportedEncodingException {
        int len = readU8(buf);
        if (len > 0) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; ++i) {
                data[i] = buf.get();
            }
            return new String(data, 0, len, "UTF-8");
        }
        return new String();
    }

    private final static void initPacket(ByteBuffer buf, int packetId) {
        buf.clear();
        writeU8(buf, packetId);
    }

    private final static void sendPacket(DatagramChannel chan, ByteBuffer buf, SocketAddress addr) throws IOException {
        buf.flip();
        chan.send(buf, addr);
    }
}
