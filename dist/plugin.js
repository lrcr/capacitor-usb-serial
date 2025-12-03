var capacitorUsbSerial = (function (exports, core) {
    'use strict';

    const UsbSerialPrimitive = core.registerPlugin('UsbSerial');
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

    exports.UsbSerial = UsbSerialPrimitive;
    exports.getDeviceHandlers = getDeviceHandlers;

    Object.defineProperty(exports, '__esModule', { value: true });

    return exports;

})({}, capacitorExports);
//# sourceMappingURL=plugin.js.map
