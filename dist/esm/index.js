import { registerPlugin } from '@capacitor/core';
const UsbSerialPrimitive = registerPlugin('UsbSerial');
const getDeviceHandlers = async () => {
    const deviceConnections = await UsbSerialPrimitive.getDeviceConnections();
    const deviceHandlers = deviceConnections.devices.map(device => ({
        device,
        async connect(options) {
            await UsbSerialPrimitive.openConnection(Object.assign({ deviceId: this.device.deviceId }, options));
        },
        async disconnect() {
            await UsbSerialPrimitive.endConnection({ key: this.device.deviceKey });
        },
        async write(message, expectedBytes) {
            const response = await UsbSerialPrimitive.write({ key: this.device.deviceKey, message, expectedBytes });
            return response;
        },
        async read(expectedBytes) {
            return await UsbSerialPrimitive.read({ key: this.device.deviceKey, expectedBytes });
        },
    }));
    return deviceHandlers;
};
export * from './definitions';
export { UsbSerialPrimitive as UsbSerial, getDeviceHandlers };
//# sourceMappingURL=index.js.map