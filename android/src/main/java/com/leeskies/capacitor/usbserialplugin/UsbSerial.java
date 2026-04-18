package com.leeskies.capacitor.usbserialplugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import android.util.Base64;

public class UsbSerial {
    private static final String TAG = "UsbSerial";
    private static final String TAG_STREAM = "UsbSerial.Stream";

    private Context context;
    private UsbManager manager;
    private Map<String, UsbSerialPort> activePorts = new ConcurrentHashMap<>();
    private Map<String, SerialInputOutputManager> streamManagers = new ConcurrentHashMap<>();
    private Map<String, Thread> rawStreamThreads = new ConcurrentHashMap<>();
    private Map<String, UsbDeviceConnection> activeConnections = new ConcurrentHashMap<>();
    private Map<String, StringBuilder> streamBuffers = new ConcurrentHashMap<>();
    private Map<String, String> streamDelimiters = new ConcurrentHashMap<>();
    private UsbSerialPlugin plugin;

    private String generatePortKey(UsbDevice device) {
        return device.getDeviceName() + "_" + device.getDeviceId();
    }

    public UsbSerial(Context context) {
        this.context = context;
        this.manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public List<JSObject> getDeviceConnections() {
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        List<JSObject> deviceList = new ArrayList<>();

        for (UsbSerialDriver driver : availableDrivers) {
            UsbDevice device = driver.getDevice();
            JSObject deviceInfo = new JSObject();
            deviceInfo.put("deviceKey", generatePortKey(device));
            deviceInfo.put("deviceId", device.getDeviceId());
            deviceInfo.put("productId", device.getProductId());
            deviceInfo.put("vendorId", device.getVendorId());
            deviceInfo.put("deviceName", device.getDeviceName());
            deviceList.add(deviceInfo);
        }

        return deviceList;
    }

    public void openConnection(PluginCall call) {
        int deviceId;
        try {
            deviceId = call.getInt("deviceId");
        } catch (NullPointerException e) {
            call.reject("DeviceId cannot be null");
            return;
        }
        for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(manager)) {
            if (driver.getDevice().getDeviceId() == deviceId) {
                UsbDevice device = driver.getDevice();
                if (!manager.hasPermission(device)) {
                    requestUsbPermission(device, call);
                    return;
                }
                proceedWithConnection(driver, device, call);
                return;
            }
        }
        call.reject("Device not found");
    }

    private void requestUsbPermission(UsbDevice device, PluginCall call) {
        String appName = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        String ACTION_USB_PERMISSION = appName + ".USB_PERMISSION";

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION),
                flags);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        BroadcastReceiver usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (usbDevice != null) {
                                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(usbDevice);
                                proceedWithConnection(driver, usbDevice, call);
                            }
                        } else {
                            call.reject("USB permission denied");
                        }
                    }
                    context.unregisterReceiver(this);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        manager.requestPermission(device, permissionIntent);
    }

    private void proceedWithConnection(UsbSerialDriver driver, UsbDevice device, PluginCall call) {
        int baudRate = call.getInt("baudRate", Const.DEFAULT_BAUD_RATE);
        int dataBits = call.getInt("dataBits", Const.DEFAULT_DATA_BITS);
        int stopBits = call.getInt("stopBits", Const.DEFAULT_STOP_BITS);
        String parityKey = call.getString("parity");
        int parity = Const.DEFAULT_PARITY;
        if (Const.PARITY.containsKey(parityKey))
            parity = Const.PARITY.get(parityKey);
        if (!Const.STOP_BITS.containsKey(stopBits))
            call.reject("Invalid int value for stopBits: " + stopBits);

        Log.i(TAG, String.format("── Connection Diagnostic ──"));
        Log.i(TAG, String.format("Device: %s (VID:%04x PID:%04x)", device.getDeviceName(), device.getVendorId(), device.getProductId()));
        Log.i(TAG, String.format("Driver: %s", driver.getClass().getSimpleName()));
        Log.i(TAG, String.format("Params: baud=%d data=%d stop=%d parity=%s", baudRate, dataBits, stopBits, parityKey));

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open device connection (USB permission issue?)");
            call.reject("Failed to open device connection");
            return;
        }
        Log.i(TAG, "USB connection opened OK");

        UsbSerialPort port = driver.getPorts().get(0);
        try {
            port.open(connection);
            Log.i(TAG, "Serial port opened OK");

            port.setParameters(baudRate, dataBits, stopBits, parity);
            Log.i(TAG, "Parameters set OK");

            // Flow control을 NONE으로 명시 설정
            // FTDI 칩 리셋 후 기본값이 hardware flow control일 수 있음
            // CTS=false 상태에서 hardware FC면 TX가 블로킹됨 → 데이터 미전송
            // SIO_SET_FLOW_CTRL = 2, wValue = 0 (NONE), wIndex = port
            try {
                int result = connection.controlTransfer(
                    0x40, 2, 0, 0, null, 0, 1000);
                Log.i(TAG, "Flow control set to NONE (result=" + result + ")");
            } catch (Exception e) {
                Log.w(TAG, "Flow control set failed: " + e.getMessage());
            }

            // RTS + DTR 활성화
            // Linux에서는 port open 시 커널이 자동으로 DTR=HIGH 설정하지만,
            // Android usb-serial-for-android는 FTDI 리셋으로 DTR=LOW가 됨
            // Philips MIB는 DTR=HIGH 필요 (상대방 ready 시그널)
            port.setDTR(true);
            port.setRTS(true);
            Log.i(TAG, "DTR + RTS set to HIGH");

            // CTS/DSR/CD/RI 상태 확인 (지원하는 드라이버만)
            try {
                boolean cts = port.getCTS();
                boolean dsr = port.getDSR();
                boolean cd = port.getCD();
                boolean ri = port.getRI();
                Log.i(TAG, String.format("Signals: CTS=%b DSR=%b CD=%b RI=%b", cts, dsr, cd, ri));
                if (!cts && !dsr) {
                    Log.w(TAG, "⚠ CTS/DSR both false — 모니터가 연결을 인식하지 못할 수 있음");
                }
            } catch (Exception e) {
                Log.w(TAG, "Signal read not supported: " + e.getMessage());
            }

            String key = generatePortKey(device);
            activePorts.put(key, port);
            activeConnections.put(key, connection);
            Log.i(TAG, String.format("Port registered: key=%s ── Connection OK ──", key));

            JSObject result = new JSObject();
            result.put("portKey", key);
            // JS 오버레이 로그용 진단 데이터
            JSObject diag = new JSObject();
            diag.put("driver", driver.getClass().getSimpleName());
            diag.put("vid", String.format("%04x", device.getVendorId()));
            diag.put("pid", String.format("%04x", device.getProductId()));
            diag.put("deviceName", device.getDeviceName());
            try {
                diag.put("cts", port.getCTS());
                diag.put("dsr", port.getDSR());
                diag.put("cd", port.getCD());
                diag.put("ri", port.getRI());
            } catch (Exception e) {
                diag.put("signalError", e.getMessage());
            }
            result.put("diagnostic", diag);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Connection failed: " + e.getMessage(), e);
            call.reject("Failed to initialize connection with selected device: " + e.getMessage());
        }
    }

    public void endConnection(PluginCall call) {
        String portKey = call.getString("key");
        if (portKey == null || !activePorts.containsKey(portKey)) {
            call.reject("Invalid port key");
            return;
        }

        try {
            stopStreamingInternal(portKey);

            UsbSerialPort port = activePorts.get(portKey);
            port.close();
            activePorts.remove(portKey);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to close port: " + e.getMessage());
        }
    }

    /**
     * Reads data from the port in chunks until no more data arrives (idle timeout).
     * This prevents data truncation when responses arrive in multiple chunks.
     * 
     * @param port The USB serial port to read from
     * @return ReadResult containing the accumulated data and total bytes read
     * @throws Exception if read operation fails
     */
    private static class ReadResult {
        String data;
        int bytesRead;
        boolean valid;
        String invalidReason;

        ReadResult(String data, int bytesRead) {
            this.data = data;
            this.bytesRead = bytesRead;
            this.valid = true;
            this.invalidReason = null;
        }

        ReadResult(String data, int bytesRead, boolean valid, String invalidReason) {
            this.data = data;
            this.bytesRead = bytesRead;
            this.valid = valid;
            this.invalidReason = invalidReason;
        }
    }

    private ReadResult readUntilIdle(UsbSerialPort port, Integer expectedBytes) throws Exception {
        StringBuilder accumulated = new StringBuilder();
        int totalBytesRead = 0;
        long startTime = System.currentTimeMillis();
        long lastDataTime = startTime;
        int chunkCount = 0;

        Log.d(TAG, "Starting readUntilIdle");

        while (true) {
            // Safety: check total elapsed time
            if (System.currentTimeMillis() - startTime > Const.READ_MAX_TOTAL_MILLIS) {
                break;
            }

            byte[] buffer = new byte[8192];
            int numBytesRead = port.read(buffer, Const.READ_CHUNK_TIMEOUT_MILLIS);

            if (numBytesRead > 0) {
                chunkCount++;
                String chunk = new String(buffer, 0, numBytesRead, StandardCharsets.UTF_8);
                accumulated.append(chunk);
                totalBytesRead += numBytesRead;
                lastDataTime = System.currentTimeMillis();

                Log.d(TAG, String.format("Chunk %d: %d bytes, data: %s (hex: %s)",
                        chunkCount, numBytesRead,
                        chunk.replace("\r", "\\r").replace("\n", "\\n"),
                        bytesToHex(buffer, numBytesRead)));

                if (totalBytesRead >= Const.READ_MAX_BUFFER_SIZE) {
                    Log.d(TAG, String.format("Buffer size limit reached (%d bytes), stopping read", totalBytesRead));
                    break;
                }
            } else {
                // Check if we've been idle long enough
                long idleTime = System.currentTimeMillis() - lastDataTime;
                if (idleTime > Const.READ_IDLE_TIMEOUT_MILLIS) {
                    Log.d(TAG, String.format("Idle timeout reached (%dms), stopping read", idleTime));
                    break;
                }
            }
        }

        String result = accumulated.toString();
        long totalTime = System.currentTimeMillis() - startTime;
        Log.d(TAG, String.format("Read complete: %d bytes in %d chunks over %dms, result: %s",
                totalBytesRead, chunkCount, totalTime,
                result.replace("\r", "\\r").replace("\n", "\\n")));

        if (expectedBytes != null && totalBytesRead != expectedBytes) {
            String reason = String.format("Expected %d bytes but got %d", expectedBytes, totalBytesRead);
            Log.w(TAG, "Invalid read: " + reason);
            return new ReadResult(result, totalBytesRead, false, reason);
        }

        return new ReadResult(result, totalBytesRead);
    }

    public void endConnections(PluginCall call) {
        List<String> errors = new ArrayList<>();

        // Stop all streaming first
        for (String key : activePorts.keySet()) {
            stopStreamingInternal(key);
        }

        for (Map.Entry<String, UsbSerialPort> entry : activePorts.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                errors.add("Failed to close port " + entry.getKey() + ": " + e.getMessage());
            }
        }
        activePorts.clear();
        if (errors.isEmpty()) {
            call.resolve();
        } else {
            call.reject("Errors occurred while closing ports: " + String.join(", ", errors));
        }
    }

    public void endConnections(PluginCall call, List<String> keys) {
        List<String> errors = new ArrayList<>();
        for (String key : keys) {
            UsbSerialPort port = activePorts.get(key);
            if (port != null) {
                try {
                    // Stop streaming first to prevent race condition crashes
                    stopStreamingInternal(key);

                    port.close();
                    activePorts.remove(key);
                } catch (Exception e) {
                    errors.add("Failed to close port " + key + ": " + e.getMessage());
                }
            } else {
                errors.add("Port not found: " + key);
            }
        }
        if (errors.isEmpty()) {
            call.resolve();
        } else {
            call.reject("Errors occurred while closing ports: " + String.join(", ", errors));
        }
    }

    private void flushInputBuffer(UsbSerialPort port) throws Exception {
        StringBuilder flushed = new StringBuilder();
        int totalFlushed = 0;
        byte[] buffer = new byte[8192];

        while (true) {
            int numBytes = port.read(buffer, 50);
            if (numBytes <= 0) {
                break;
            }
            totalFlushed += numBytes;
            String chunk = new String(buffer, 0, numBytes, StandardCharsets.UTF_8);
            flushed.append(chunk);
        }

        if (totalFlushed > 0) {
            Log.d(TAG, String.format("Flushed %d bytes from buffer: %s (hex: %s)",
                    totalFlushed,
                    flushed.toString().replace("\r", "\\r").replace("\n", "\\n"),
                    bytesToHex(flushed.toString().getBytes(StandardCharsets.UTF_8), totalFlushed)));
        } else {
            Log.d(TAG, "Buffer was clean (no pending data)");
        }
    }

    public void write(PluginCall call) {
        String portKey = call.getString("key");
        String message = call.getString("message");
        Boolean noRead = call.getBoolean("noRead", false);
        Integer expectedBytes = call.getInt("expectedBytes");

        UsbSerialPort port = activePorts.get(portKey);
        if (port == null) {
            call.reject("Specified port not found: " + portKey);
            return;
        }

        try {
            long writeStartTime = System.currentTimeMillis();

            // 스트리밍 활성화 상태에서는 flushInputBuffer 스킵 (read 스레드 충돌 방지)
            if (!streamManagers.containsKey(portKey)) {
                flushInputBuffer(port);
            }

            // base64 디코딩: JS에서 bytesToBase64()로 인코딩된 바이너리 복원
            byte[] messageBytes = Base64.decode(message, Base64.NO_WRAP);
            Log.d(TAG, String.format("Writing %d bytes (hex: %s)",
                    messageBytes.length,
                    bytesToHex(messageBytes, Math.min(messageBytes.length, 32))));

            long beforeWrite = System.currentTimeMillis();
            port.write(messageBytes, Const.WRITE_WAIT_MILLIS);
            long afterWrite = System.currentTimeMillis();

            Log.d(TAG, String.format("Write completed in %dms", afterWrite - beforeWrite));

            if (!noRead) {
                ReadResult result = readUntilIdle(port, expectedBytes);

                JSObject response = new JSObject();
                response.put("data", result.data);
                response.put("bytesRead", result.bytesRead);
                response.put("valid", result.valid);
                if (result.invalidReason != null) {
                    response.put("invalidReason", result.invalidReason);
                }
                call.resolve(response);
            } else {
                JSObject result = new JSObject();
                result.put("data", "");
                result.put("bytesRead", 0);
                result.put("valid", true);
                call.resolve(result);
            }

            long totalTime = System.currentTimeMillis() - writeStartTime;
            Log.d(TAG, String.format("Total write operation took %dms", totalTime));
        } catch (Exception e) {
            call.reject("Communication with port failed: " + e.getMessage());
        }
    }

    public void read(PluginCall call) {
        String portKey = call.getString("key");
        Integer expectedBytes = call.getInt("expectedBytes");

        UsbSerialPort port = activePorts.get(portKey);
        if (port == null) {
            call.reject("Specified port not found");
            return;
        }
        try {
            ReadResult result = readUntilIdle(port, expectedBytes);

            JSObject response = new JSObject();
            response.put("data", result.data);
            response.put("bytesRead", result.bytesRead);
            response.put("valid", result.valid);
            if (result.invalidReason != null) {
                response.put("invalidReason", result.invalidReason);
            }
            call.resolve(response);
        } catch (Exception e) {
            call.reject("Failed to read data: " + e.getMessage());
        }
    }

    public void getActivePorts(PluginCall call) {
        JSObject result = new JSObject();
        JSArray keysArray = new JSArray();

        for (String key : activePorts.keySet()) {
            keysArray.put(key);
        }

        result.put("ports", keysArray);
        call.resolve(result);
    }

    public void setPlugin(UsbSerialPlugin plugin) {
        this.plugin = plugin;
    }

    public void startStreaming(PluginCall call) {
        String portKey = call.getString("key");
        String delimiter = call.getString("delimiter", "\n");

        Log.d(TAG_STREAM, String.format("[%s] Starting stream (binary=%b)",
                portKey, delimiter.isEmpty()));

        UsbSerialPort port = activePorts.get(portKey);
        if (port == null) {
            call.reject("Port not found: " + portKey);
            return;
        }

        // Stop existing stream if any
        stopStreamingInternal(portKey);

        streamDelimiters.put(portKey, delimiter);
        streamBuffers.put(portKey, new StringBuilder());

        // 바이너리 모드 (delimiter 빈 문자열): port.read() 스레드 사용
        // port.read()는 port.write()와 동일한 직렬 포트 추상화를 사용하므로 충돌 없음
        // (이전: raw bulkTransfer가 port.write()와 다른 API 레이어 사용 → 엔드포인트 경쟁)
        if (delimiter.isEmpty()) {
            UsbSerialPort serialPort = activePorts.get(portKey);
            if (serialPort == null) {
                call.reject("No port for key: " + portKey);
                return;
            }

            Thread readThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                int readCount = 0;
                int consecutiveErrors = 0;
                Log.i(TAG_STREAM, String.format("[%s] Serial port stream started", portKey));
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        int n = serialPort.read(buf, 200);
                        readCount++;
                        if (n > 0) {
                            consecutiveErrors = 0;
                            byte[] serialData = new byte[n];
                            System.arraycopy(buf, 0, serialData, 0, n);
                            Log.d(TAG_STREAM, String.format("[%s] read#%d: %d bytes", portKey, readCount, n));
                            processStreamData(portKey, serialData);
                        } else if (n == 0) {
                            // 데이터 없음 — 정상
                            consecutiveErrors = 0;
                        }
                        if (n < 0) {
                            consecutiveErrors++;
                            if (consecutiveErrors >= 50) {
                                Log.e(TAG_STREAM, String.format("[%s] %d consecutive read errors, stopping", portKey, consecutiveErrors));
                                notifyStreamError(portKey, "Serial port read failed");
                                break;
                            }
                            try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                        }
                    } catch (Exception e) {
                        Log.e(TAG_STREAM, String.format("[%s] Read error: %s", portKey, e.getMessage()));
                        notifyStreamError(portKey, "Read error: " + e.getMessage());
                        break;
                    }
                }
                Log.i(TAG_STREAM, String.format("[%s] Serial port stream ended (%d reads)", portKey, readCount));
            }, "RawBulkStream-" + portKey);
            rawStreamThreads.put(portKey, readThread);
            readThread.start();
            Log.d(TAG_STREAM, String.format("[%s] Stream started successfully (raw bulk mode)", portKey));
            call.resolve();
            return;
        }

        // 텍스트 모드: 기존 SerialInputOutputManager 사용
        SerialInputOutputManager.Listener listener = new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                try {
                    processStreamData(portKey, data);
                } catch (Exception e) {
                    Log.e(TAG_STREAM, String.format("[%s] Error processing data: %s", portKey, e.getMessage()), e);
                    notifyStreamError(portKey, "Error processing data: " + e.getMessage());
                }
            }

            @Override
            public void onRunError(Exception e) {
                Log.e(TAG_STREAM, String.format("[%s] Serial IO Error: %s", portKey, e.getMessage()), e);
                notifyStreamError(portKey, "Serial IO Error: " + e.getMessage());
            }
        };

        SerialInputOutputManager manager = new SerialInputOutputManager(port, listener);
        streamManagers.put(portKey, manager);
        manager.start();
        Log.d(TAG_STREAM, String.format("[%s] Stream started successfully", portKey));
        call.resolve();
    }

    public void stopStreaming(PluginCall call) {
        String portKey = call.getString("key");
        stopStreamingInternal(portKey);
        call.resolve();
    }

    private void stopStreamingInternal(String portKey) {
        SerialInputOutputManager manager = streamManagers.get(portKey);
        if (manager != null) {
            Log.d(TAG_STREAM, String.format("[%s] Stopping stream (SIO)", portKey));
            manager.stop();
            streamManagers.remove(portKey);
        }
        Thread rawThread = rawStreamThreads.get(portKey);
        if (rawThread != null) {
            Log.d(TAG_STREAM, String.format("[%s] Stopping stream (raw bulk)", portKey));
            rawThread.interrupt();
            rawStreamThreads.remove(portKey);
        }
        streamBuffers.remove(portKey);
        streamDelimiters.remove(portKey);
    }

    private String stripControlChars(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c == '\r' || c == '\n' || (c >= 0x20 && c < 0x7F)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void processStreamData(String portKey, byte[] data) {
        try {
            String delimiter = streamDelimiters.get(portKey);

            // 바이너리 모드: delimiter가 비어있으면 raw bytes를 base64로 즉시 전송
            if (delimiter != null && delimiter.isEmpty()) {
                String base64Data = Base64.encodeToString(data, Base64.NO_WRAP);
                emitDataReceived(portKey, base64Data, base64Data);
                return;
            }

            // 텍스트 모드: 기존 delimiter 기반 처리
            String rawChunk = stripControlChars(new String(data, StandardCharsets.UTF_8));
            StringBuilder buffer = streamBuffers.get(portKey);

            if (buffer == null) {
                Log.w(TAG_STREAM, String.format("[%s] Buffer is null, ignoring data", portKey));
                return;
            }

            buffer.append(rawChunk);

            String bufferStr = buffer.toString();
            int pos = 0;

            while (pos < bufferStr.length()) {
                int delimiterStart = -1;
                for (int i = pos; i < bufferStr.length(); i++) {
                    char c = bufferStr.charAt(i);
                    if (c == '\r' || c == '\n') {
                        delimiterStart = i;
                        break;
                    }
                }

                if (delimiterStart == -1) {
                    break;
                }

                int delimiterEnd = delimiterStart;
                while (delimiterEnd < bufferStr.length()) {
                    char c = bufferStr.charAt(delimiterEnd);
                    if (c != '\r' && c != '\n') {
                        break;
                    }
                    delimiterEnd++;
                }

                String message = bufferStr.substring(pos, delimiterStart);
                String delim = bufferStr.substring(delimiterStart, delimiterEnd);
                String completeRawMessage = message + delim;

                if (message.length() > 0) {
                    emitDataReceived(portKey, message, completeRawMessage);
                }

                pos = delimiterEnd;
            }

            buffer.setLength(0);
            String remaining = (pos < bufferStr.length()) ? bufferStr.substring(pos) : "";
            buffer.append(remaining);
        } catch (Exception e) {
            Log.e(TAG_STREAM, String.format("[%s] Parsing error: %s", portKey, e.getMessage()), e);
            notifyStreamError(portKey, "Parsing error: " + e.getMessage());
        }
    }

    private void emitDataReceived(String portKey, String data, String rawData) {
        if (plugin == null) {
            Log.w(TAG_STREAM, String.format("[%s] Plugin is null, cannot emit data", portKey));
            return;
        }

        JSObject event = new JSObject();
        event.put("key", portKey);
        event.put("data", data);
        event.put("rawData", rawData);

        Log.d(TAG_STREAM, String.format("[%s] Emitting to JS - data: %s, rawData: %s",
                portKey, data, rawData.replace("\r", "\\r").replace("\n", "\\n")));

        plugin.notifyDataReceived(event);
    }

    private void notifyStreamError(String portKey, String error) {
        if (plugin == null) {
            Log.w(TAG_STREAM, String.format("[%s] Plugin is null, cannot emit error", portKey));
            return;
        }

        JSObject event = new JSObject();
        event.put("key", portKey);
        event.put("error", error);

        Log.e(TAG_STREAM, String.format("[%s] Emitting error to JS: %s", portKey, error));

        plugin.notifyStreamError(event);
    }

    // Helper method to convert bytes to hex string for logging
    private static String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(length, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

}