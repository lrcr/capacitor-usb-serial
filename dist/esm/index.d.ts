import { UsbSerialPlugin, GetDeviceHandlers } from './definitions';
declare const UsbSerialPrimitive: UsbSerialPlugin;
declare const getDeviceHandlers: GetDeviceHandlers;
export * from './definitions';
export { UsbSerialPrimitive as UsbSerial, getDeviceHandlers };
