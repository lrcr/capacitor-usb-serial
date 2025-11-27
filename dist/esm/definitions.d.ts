/** Represents the response from a read operation */
export declare type ReadResponse = {
    data: string;
    bytesRead: number;
};
/** Defines the interface for USB serial communication plugin */
export interface UsbSerialPlugin {
    /**
     * Returns all connected devices
     * @returns {Promise<{devices: DeviceInfo[]}>} A promise that resolves to an object containing an array of connected devices
     */
    getDeviceConnections(): Promise<{
        devices: DeviceInfo[];
    }>;
    /**
     * Connect to a device using its deviceId
     * @param {FullConnectionParams} options - Connection parameters including deviceId
     * @returns {Promise<{portKey: string}>} A promise that resolves to an object containing the assigned portKey
     */
    openConnection(options: FullConnectionParams): Promise<{
        portKey: string;
    }>;
    /**
     * Returns all active ports
     * @returns {Promise<{ports: string[]}>} A promise that resolves to an object containing an array of active port keys
     */
    getActivePorts(): Promise<{
        ports: string[];
    }>;
    /**
     * Disconnect from a device using its assigned portKey
     * @param {{key: string}} options - Object containing the portKey
     * @returns {Promise<void>} A promise that resolves when the connection is closed
     */
    endConnection(options: {
        key: string;
    }): Promise<void>;
    /**
     * Disconnect from all devices or specified devices
     * @param {{keys?: string[]}} [options] - Optional object containing an array of portKeys to disconnect
     * @returns {Promise<void>} A promise that resolves when all specified connections are closed
     */
    endConnections(options?: {
        keys?: string[];
    }): Promise<void>;
    /**
     * Write a message to a device using its assigned portKey
     * @param {{key: string, message: string, noRead?: boolean}} options - Object containing the portKey and message to write. Pass noRead to skip the immediate read response
     * @returns {Promise<ReadResponse>} A promise that resolves when the message is written
     */
    write(options: {
        key: string;
        message: string;
        noRead?: boolean;
    }): Promise<ReadResponse>;
    /**
     * Read a message from a device using its assigned portKey
     * @param {{key: string}} options - Object containing the portKey
     * @returns {Promise<ReadResponse>} A promise that resolves to the read response
     */
    read(options: {
        key: string;
    }): Promise<ReadResponse>;
}
/**
 * Represents parity options for serial communication
 * none => 0, even => 1, odd => 2, mark => 3, space => 4
 */
export declare type Parity = 'none' | 'even' | 'odd' | 'mark' | 'space';
/** Defines basic connection parameters */
export interface ConnectionParams {
    /** Baud rate (defaults to 9600) */
    baudRate?: number;
    /** Number of data bits (defaults to 8) */
    dataBits?: number;
    /** Number of stop bits (defaults to 1) */
    stopBits?: number;
    /** Parity setting (defaults to 'none') */
    parity?: Parity;
}
/** Extends ConnectionParams to include deviceId */
export interface FullConnectionParams extends ConnectionParams {
    /** Unique identifier for the device */
    deviceId: number;
}
/** Represents information about a connected device */
export interface DeviceInfo {
    /** Unique identifier used internally */
    deviceKey: string;
    /** Numeric identifier for the device */
    deviceId: number;
    /** Product ID of the device */
    productId: number;
    /** Vendor ID of the device */
    vendorId: number;
    /** Human-readable name of the device */
    deviceName: string;
}
/** Provides a simplified interface for handling a specific device */
export interface DeviceHandler {
    /** The device being handled */
    device: DeviceInfo;
    /**
     * Connect to the device (shorthand for UsbSerial.openConnection)
     * @returns {Promise<void>} A promise that resolves when the connection is established
     */
    connect(): Promise<void>;
    /**
     * Disconnect from the device (shorthand for UsbSerial.endConnection)
     * @returns {Promise<void>} A promise that resolves when the connection is closed
     */
    disconnect(): Promise<void>;
    /**
     * Write a message to the device (shorthand for UsbSerial.write)
     * @param {string} message - The message to write
     * @returns {Promise<ReadResponse>} A promise that resolves when the message is written
     */
    write(message: string): Promise<ReadResponse>;
    /**
     * Read from the device (shorthand for UsbSerial.read)
     * @returns {Promise<ReadResponse>} A promise that resolves to the read response
     */
    read(): Promise<ReadResponse>;
}
/**
 * Retrieves and constructs DeviceHandler objects for all connected USB devices.
 *
 * This function performs the following steps:
 * 1. Fetches all device connections using UsbSerialPrimitive.getDeviceConnections().
 * 2. Maps each device to a DeviceHandler object, which provides simplified methods
 *    for connecting, disconnecting, writing to, and reading from the device.
 *
 * @returns {Promise<DeviceHandler[]>} A promise that resolves to an array of DeviceHandler objects,
 * each representing a connected USB device with methods to interact with it.
 *
 * @throws {Error} If there's an issue fetching device connections or constructing DeviceHandlers.
 *
 * @example
 * const handlers = await getDeviceHandlers();
 * if (handlers.length > 0) {
 *   const firstDevice = handlers[0];
 *   await firstDevice.connect();
 *   await firstDevice.write("Hello, device!");
 *   const response = await firstDevice.read();
 *   console.log(response.data);
 *   await firstDevice.disconnect();
 * }
 */
export declare type GetDeviceHandlers = () => Promise<DeviceHandler[]>;
