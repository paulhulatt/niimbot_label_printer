## 0.0.3

* Fix: Resolved Bluetooth socket initialization issue causing blank labels on first print
* Fix: Store OutputStream and InputStream as member variables after connection
* Fix: Pass stored streams to NiimbotPrinter instead of letting it access socket getters repeatedly
* Fix: Prevents uninitialized stream instances from being used on first print
* Improvement: Added stream verification and error handling in connect method
* Improvement: Enhanced disconnect method to properly close and clear stored streams
* Improvement: Added logging for better debugging
* Breaking: NiimbotPrinter constructor now accepts (Context, OutputStream, InputStream) instead of (Context, BluetoothSocket)

## 0.0.2

* Add labelType to PrintData
* Add image to example desing image of canvas

## 0.0.1

* Upload all files
