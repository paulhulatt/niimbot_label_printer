package com.example.niimbot_label_printer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.NonNull
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** NiimbotLabelPrinterPlugin */
class NiimbotLabelPrinterPlugin : FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var TAG: String = "====> NiimbotLabelPrinterPlugin: "
    private lateinit var channel: MethodChannel
    private lateinit var mContext: Context
    private var state: Boolean = false

    //val pluginActivity: Activity = activity
    //private val application: Application = activity.application
    private val myPermissionCode = 34264
    private var activeResult: Result? = null
    private var permissionGranted: Boolean = false

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private lateinit var mac: String
    private lateinit var niimbotPrinter: NiimbotPrinter

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "niimbot_label_printer")
        channel.setMethodCallHandler(this)
        this.mContext = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        var sdkversion: Int = Build.VERSION.SDK_INT

        activeResult = result
        permissionGranted = ContextCompat.checkSelfPermission(
            mContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (call.method == "ispermissionbluetoothgranted") {
            var permission: Boolean = true;
            if (sdkversion >= 31) {
                permission = permissionGranted;
            }
            //solicitar el permiso si no esta consedido
            if (!permission) {
                // Solicitar el permiso si no esta consedido
            }

            result.success(permission)
        } else if (!permissionGranted && sdkversion >= 31) {
            Log.i(
                "warning",
                "Permission bluetooth granted is false, check in settings that the permission of nearby devices is activated"
            )
            return;
        } else if (call.method == "getPlatformVersion") {
            var androidVersion: String = android.os.Build.VERSION.RELEASE;
            result.success("Android ${androidVersion}")
        } else if (call.method == "isBluetoothEnabled") {
            var state: Boolean = false
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                state = true
            }
            result.success(state)
        } else if (call.method == "isConnected") {
            if (bluetoothSocket != null) {
                try {
                    bluetoothSocket?.outputStream?.run {
                        write(" ".toByteArray())
                        result.success(true)
                        //Log.d(TAG, "paso yes coexion ")
                    }
                } catch (e: Exception) {
                    result.success(false)
                    bluetoothSocket = null
                    //mensajeToast("Dispositivo fue desconectado, reconecte")
                    //Log.d(TAG, "state print: ${e.message}")
                }
            } else {
                result.success(false)
                //Log.d(TAG, "no paso es false ")
            }
        } else if (call.method == "getPairedDevices") {
            var lista: List<String> = dispositivosVinculados()

            result.success(lista)
        } else if (call.method == "connect") {
            var macimpresora = call.arguments.toString();
            //Log.d(TAG, "coneccting kt: mac: "+macimpresora);
            if (macimpresora.length > 0) {
                mac = macimpresora;
            } else {
                result.success(false)
            }

            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                    if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                        val device = bluetoothAdapter.getRemoteDevice(mac)
                        bluetoothSocket = device?.createRfcommSocketToServiceRecord(
                            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                        )
                        
                        // Establish the connection
                        bluetoothSocket?.connect()
                        
                        // Verify socket is connected and store streams
                        if (bluetoothSocket?.isConnected == true) {
                            try {
                                // FIX: Get and STORE the streams as member variables
                                // This ensures we use the same stream instances throughout
                                // rather than getting fresh (potentially uninitialized) references each time
                                outputStream = bluetoothSocket?.outputStream
                                inputStream = bluetoothSocket?.inputStream
                                
                                if (outputStream != null && inputStream != null) {
                                    Log.d(TAG, "Streams obtained and stored successfully")
                                    
                                    // FIX: Initialize printer communication immediately after connection
                                    // Send multiple commands to fully establish the communication pattern
                                    // This ensures the printer is ready before the first real print job
                                    try {
                                        Log.d(TAG, "Initializing printer state...")
                                        
                                        // Command 1: Heartbeat - establishes basic communication
                                        val heartbeatPacket = createPacket(0xDC.toByte(), byteArrayOf(1))
                                        outputStream!!.write(heartbeatPacket)
                                        outputStream!!.flush()
                                        Thread.sleep(150)
                                        if (inputStream!!.available() > 0) {
                                            val buffer = ByteArray(1024)
                                            inputStream!!.read(buffer)
                                        }
                                        
                                        // Command 2: Get printer info - exercises bidirectional communication
                                        val infoPacket = createPacket(0x40, byteArrayOf(1)) // RFIDINFO
                                        outputStream!!.write(infoPacket)
                                        outputStream!!.flush()
                                        Thread.sleep(150)
                                        if (inputStream!!.available() > 0) {
                                            val buffer = ByteArray(1024)
                                            inputStream!!.read(buffer)
                                        }
                                        
                                        // Command 3: Allow print clear - initializes print state
                                        val clearPacket = createPacket(0x20, byteArrayOf(1))
                                        outputStream!!.write(clearPacket)
                                        outputStream!!.flush()
                                        Thread.sleep(150)
                                        if (inputStream!!.available() > 0) {
                                            val buffer = ByteArray(1024)
                                            inputStream!!.read(buffer)
                                        }
                                        
                                        Log.d(TAG, "Printer initialization complete")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Printer initialization warning: ${e.message}")
                                        // Non-fatal - continue even if initialization has issues
                                    }
                                    
                                    result.success(true)
                                } else {
                                    Log.e(TAG, "Failed to initialize streams")
                                    outputStream = null
                                    inputStream = null
                                    bluetoothSocket?.close()
                                    bluetoothSocket = null
                                    result.success(false)
                                }
                            } catch (e: IOException) {
                                Log.e(TAG, "Stream access error: ${e.message}")
                                outputStream = null
                                inputStream = null
                                bluetoothSocket?.close()
                                bluetoothSocket = null
                                result.success(false)
                            }
                        } else {
                            Log.e(TAG, "Socket not connected")
                            result.success(false)
                        }
                    } else {
                        result.success(false)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Connection error: ${e.message}")
                    e.printStackTrace()
                    bluetoothSocket?.close()
                    bluetoothSocket = null
                    result.success(false)
                }
            }
        } else if (call.method == "send") {
            val datosImagen = call.arguments as Map<String, Any>

            // B21, B1, B18: máx. 384 píxeles (casi igual a 50 mm * 8 px/mm = 400)
            // D11: máx. 96 píxeles (casi igual a 15 mm * 8 px/mm = 120)
            // B1: 400 ancho x 240 alto
            // Se sacan los porcentajes asi: si es lable 50 x 30 se multiplica por 8 pixeles, ejemplo 50*8=400 y 30*8=240

            // Extraer los datos del mapa
            val bytes = (datosImagen["bytes"] as? List<Int>)?.map { it.toByte() }?.toByteArray()
            val width = (datosImagen["width"] as? Int) ?: 0
            val height = (datosImagen["height"] as? Int) ?: 0
            val rotate = (datosImagen["rotate"] as? Boolean) ?: false
            val invertColor = (datosImagen["invertColor"] as? Boolean) ?: false
            val density = (datosImagen["density"] as? Int) ?: 3
            val labelType = (datosImagen["labelType"] as? Int) ?: 1
            //println("0. width: $width height: $height")

            if (bytes != null && width > 0 && height > 0) {
                // Verifica que el tamaño del buffer sea correcto
                val expectedBufferSize = width * height * 4 // 4 bytes por píxel (ARGB_8888)
                if (bytes.size != expectedBufferSize) {
                    throw IllegalArgumentException("Buffer not large enough for pixels: expected $expectedBufferSize but got ${bytes.size}")
                }
                // Crear un Bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // Copiar los bytes al Bitmap
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

                // FIX: Use stored streams instead of socket
                if (outputStream != null && inputStream != null) {
                    niimbotPrinter = NiimbotPrinter(mContext, outputStream!!, inputStream!!)
                    GlobalScope.launch {
                        niimbotPrinter.printBitmap(bitmap, density = density, labelType = labelType, rotate = rotate, invertColor = invertColor)
                        result.success(true)
                    }
                } else {
                    Log.e(TAG, "No hay conexión Bluetooth establecida o streams no disponibles")
                    result.success(false)
                }
            } else {
                println("Datos de imagen inválidos o incompletos")
                println("bytes: $bytes")
                println("width: $width")
                println("height: $height")
                result.success(false)
            }

        } else if (call.method == "disconnect") {
            disconncet()
            result.success(true)
        } else {
            result.notImplemented()
        }
    }

    private fun dispositivosVinculados(): List<String> {

        val listItems: MutableList<String> = mutableListOf()

        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            //lblmsj.setText("Esta aplicacion necesita de un telefono con bluetooth")
        }
        //si no esta prendido
        if (bluetoothAdapter?.isEnabled == false) {
            //val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            //startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            //mensajeToast("Bluetooth off")
        }
        //buscar bluetooth
        //Log.d(TAG, "buscando dispositivos: ")
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        pairedDevices?.forEach { device ->
            val deviceName = device.name
            val deviceHardwareAddress = device.address
            listItems.add("$deviceName#$deviceHardwareAddress")
            //Log.d(TAG, "dispositivo: ${device.name}")
        }

        return listItems;
    }

    private suspend fun connect(): OutputStream? {
        //state = false
        return withContext(Dispatchers.IO) {
            var outputStream: OutputStream? = null
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                try {
                    val bluetoothAddress =
                        mac//"66:02:BD:06:18:7B" // replace with your device's address
                    val bluetoothDevice = bluetoothAdapter.getRemoteDevice(bluetoothAddress)
                    val bluetoothSocket = bluetoothDevice?.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    )
                    bluetoothAdapter.cancelDiscovery()
                    bluetoothSocket?.connect()
                    if (bluetoothSocket!!.isConnected) {
                        outputStream = bluetoothSocket!!.outputStream
                        state = true
                        //outputStream.write("\n".toByteArray())
                    } else {
                        state = false
                        Log.d(TAG, "Desconectado: ")
                    }
                    //bluetoothSocket?.close()
                } catch (e: Exception) {
                    state = false
                    var code: Int = e.hashCode() //1535159 apagado //
                    Log.d(TAG, "connect: ${e.message} code $code")
                    outputStream?.close()
                }
            } else {
                state = false
                Log.d(TAG, "Problema adapter: ")
            }
            outputStream
        }
    }

    private fun createPacket(type: Byte, data: ByteArray): ByteArray {
        val packetData = ByteBuffer.allocate(data.size + 7)
            .put(0x55.toByte()).put(0x55.toByte()) // Header
            .put(type)
            .put(data.size.toByte())
            .put(data)

        var checksum = type.toInt() xor data.size
        data.forEach { checksum = checksum xor it.toInt() }

        packetData.put(checksum.toByte())
            .put(0xAA.toByte()).put(0xAA.toByte()) // Footer

        return packetData.array()
    }

    private fun disconncet() {
        try {
            // Close stored streams first
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream: ${e.message}")
            }
            
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing input stream: ${e.message}")
            }
            
            // Then close socket
            bluetoothSocket?.close()
            
            // Clear all references
            outputStream = null
            inputStream = null
            bluetoothSocket = null
            
            Log.d(TAG, "Disconnected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}


