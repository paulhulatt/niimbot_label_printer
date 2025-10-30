## 0.0.3

* Fix: Resolved blank labels on first print by initializing printer communication immediately after connection
* Fix: Send initialization command sequence (heartbeat, get info, allow clear) after connection to establish communication pattern
* Fix: Added heartbeat command after startPrint() to absorb dropped packet (Niimbot Bluetooth protocol workaround)
* Fix: Added allowPrintClear() call in proper sequence before startPagePrint()
* Fix: Store OutputStream and InputStream as member variables after connection
* Fix: Pass stored streams to NiimbotPrinter instead of letting it access socket getters repeatedly
* Fix: Prevents uninitialized stream instances from being used on first print
* Improvement: Printer state is fully initialized before first print job
* Improvement: Corrected print command sequence to match Niimbot protocol specification
* Improvement: Added stream verification and error handling in connect method
* Improvement: Enhanced disconnect method to properly close and clear stored streams
* Improvement: Added comprehensive logging for better debugging
* Breaking: NiimbotPrinter constructor now accepts (Context, OutputStream, InputStream) instead of (Context, BluetoothSocket)

## 0.0.2

* Add labelType to PrintData
* Add image to example desing image of canvas

## 0.0.1

* Upload all files
