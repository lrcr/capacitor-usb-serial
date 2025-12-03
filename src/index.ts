import { registerPlugin } from '@capacitor/core';

import { DeviceHandler, UsbSerialPlugin, ReadResponse, ConnectionParams, GetDeviceHandlers } from './definitions';

const UsbSerialPrimitive =
  registerPlugin<UsbSerialPlugin>('UsbSerial');

  const getDeviceHandlers: GetDeviceHandlers = async () => {
    const deviceConnections = await UsbSerialPrimitive.getDeviceConnections();
    const deviceHandlers: DeviceHandler[] = deviceConnections.devices.map(
      device => ({
        device,
        async connect(options?: ConnectionParams): Promise<void> {
          await UsbSerialPrimitive.openConnection({ deviceId: this.device.deviceId, ...options });
        },

        async disconnect(): Promise<void> {
          await UsbSerialPrimitive.endConnection({ key: this.device.deviceKey });
        },
        async write(message: string, expectedBytes?: number): Promise<ReadResponse> {
          const response = await UsbSerialPrimitive.write({ key: this.device.deviceKey, message, expectedBytes });
          return response
        },
        async read(expectedBytes?: number): Promise<ReadResponse> {
          return await UsbSerialPrimitive.read({ key: this.device.deviceKey, expectedBytes });
        },
      }),
    );
    return deviceHandlers;
  }

export * from './definitions';
export { UsbSerialPrimitive as UsbSerial, getDeviceHandlers };
