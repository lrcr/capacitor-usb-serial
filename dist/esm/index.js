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
        async write(message) {
            const response = await UsbSerialPrimitive.write({ key: this.device.deviceKey, message });
            return response;
        },
        async read() {
            return await UsbSerialPrimitive.read({ key: this.device.deviceKey });
        },
    }));
    return deviceHandlers;
};
export * from './definitions';
export { UsbSerialPrimitive as UsbSerial, getDeviceHandlers };
//# sourceMappingURL=index.js.map