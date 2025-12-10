package com.leeskies.capacitor.usbserialplugin;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Const {
    static final int WRITE_WAIT_MILLIS = 100;
    static final int READ_WAIT_MILLIS = 100;
    // Timeout for each read chunk - increased to handle slower devices and USB
    // polling delays
    static final int READ_CHUNK_TIMEOUT_MILLIS = 100;
    // Time to wait with no data before considering read complete - increased to
    // prevent premature cutoff
    static final int READ_IDLE_TIMEOUT_MILLIS = 100;
    // Safety limit for total read duration
    static final int READ_MAX_TOTAL_MILLIS = 3000;
    static final int DEFAULT_BAUD_RATE = 9600;
    static final int DEFAULT_DATA_BITS = 8;
    static final int DEFAULT_STOP_BITS = UsbSerialPort.STOPBITS_1;
    static final int DEFAULT_PARITY = UsbSerialPort.PARITY_NONE;
    static final String ACTION_USB_PERMISSION = "";
    public static final Map<String, Integer> PARITY;
    public static final Map<Integer, Integer> STOP_BITS;

    static {
        Map<String, Integer> map = new HashMap<>();
        map.put("none", UsbSerialPort.PARITY_NONE);
        map.put("odd", UsbSerialPort.PARITY_ODD);
        map.put("even", UsbSerialPort.PARITY_EVEN);
        map.put("mark", UsbSerialPort.PARITY_MARK);
        map.put("space", UsbSerialPort.PARITY_SPACE);
        PARITY = Collections.unmodifiableMap(map);
    }

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(1, UsbSerialPort.STOPBITS_1);
        map.put(2, UsbSerialPort.STOPBITS_2);
        map.put(3, UsbSerialPort.STOPBITS_1_5);
        STOP_BITS = Collections.unmodifiableMap(map);
    }

    private Const() {
    };
}
