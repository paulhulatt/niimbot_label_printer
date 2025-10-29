package com.example.niimbot_label_printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.experimental.or
import kotlin.math.ceil

// https://github.com/AndBondStyle/niimprint/blob/main/readme.md
// FIX: Accept streams directly instead of socket to ensure we use the same stream instances
class NiimbotPrinter(private val context: Context, private val outputStream: OutputStream, private val inputStream: InputStream) {

    private suspend fun sendCommand(requestCode: Byte, data: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val packet = createPacket(requestCode, data)
        // FIX: Use the stored streams passed from plugin
        outputStream.write(packet)
        outputStream.flush()

        Thread.sleep(100) //delay(100) // Ajusta este delay según sea necesario

        val buffer = ByteArray(1024)
        val bytes = inputStream.read(buffer)
        return@withContext buffer.copyOfRange(0, bytes)
    }

    private fun createPacket(type: Byte, data: ByteArray): ByteArray {
        val packetData = ByteBuffer.allocate(data.size + 7) // Aumentamos el tamaño en 1
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

    suspend fun printBitmap(bitmap: Bitmap, density: Int = 3, labelType: Int = 1, quantity: Int = 1, rotate: Boolean = false, invertColor: Boolean = false) {
        var bitmap = bitmap
        var width:Int = bitmap.width
        var height:Int = bitmap.height
        //println("1. width: $width height: $height")
        if (rotate) {
            //bitmap = bitmap.rotate90Clockwise()
            bitmap = rotateBitmap90Degrees(bitmap)
            width = bitmap.width
            height = bitmap.height
            //println("2. width: $width height: $height")
        }

        if(invertColor) {
            bitmap = bitmap.invert()
        }
        //println("Loading image...")
        //val bitmap = loadImageFromAssets(imageName)
        //println("Setting label density...")
        setLabelDensity(density)
        //println("Setting label type...")
        setLabelType(labelType)
        //println("Starting print...")
        startPrint()
        //println("Starting page print...")
        startPagePrint()
        //println("Setting image dimensions...")
        setDimension(height, width)
        //println("Setting quantity...")
        setQuantity(quantity)
        //println("Printing image...")

        for (packet in encodeImage(bitmap)) {
            // FIX: Use stored stream instead of accessing socket
            outputStream.write(packet)
            outputStream.flush()
            delay(10) // Pequeña pausa entre paquetes
        }

        //println("Printing page...")

        while (!endPagePrint()) {
            delay(50)
        }

        while (true) {
            val status = getPrintStatus()
            if (status["page"] == quantity) break
            delay(100)
        }

        endPrint()
    }

    fun rotateBitmap90Degrees(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            postRotate(90f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun Bitmap.rotate90Clockwise(): Bitmap {
        val matrix = Matrix()
        matrix.setRotate(90f)
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }


    private suspend fun loadImageFromAssets(imageName: String): Bitmap =
        withContext(Dispatchers.IO) {
            val inputStream = context.assets.open(imageName)
            BitmapFactory.decodeStream(inputStream)
        }

    private fun encodeImage(bitmap: Bitmap): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val invertedBitmap = bitmap; //bitmap.invert()

        for (y in 0 until invertedBitmap.height) {
            val lineData = ByteArray(ceil(invertedBitmap.width / 8.0).toInt())
            for (x in 0 until invertedBitmap.width) {
                val pixel = invertedBitmap.getPixel(x, y)
                if (pixel == 0xFF000000.toInt()) { // Black pixel
                    lineData[x / 8] = lineData[x / 8] or (1 shl (7 - x % 8)).toByte()
                }
            }

            val header = ByteBuffer.allocate(6)
                .putShort(y.toShort())
                .put(0.toByte()).put(0.toByte()).put(0.toByte()) // counts
                .put(1.toByte())
                .array()

            val packetData = header + lineData
            packets.add(createPacket(0x85.toByte(), packetData))
        }

        return packets
    }

    private fun Bitmap.invert(): Bitmap {
        val invertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(invertedBitmap)
        val paint = android.graphics.Paint()
        val colorMatrix = android.graphics.ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(this, 0f, 0f, paint)
        return invertedBitmap
    }

    suspend fun setLabelDensity(n: Int): Boolean {
        require(n in 1..5) { "Density must be between 1 and 5" }
        val response = sendCommand(0x21, byteArrayOf(n.toByte()))
        return response[4] != 0.toByte()
    }

    suspend fun setLabelType(n: Int): Boolean {
        require(n in 1..3) { "Label type must be between 1 and 3" }
        val response = sendCommand(0x23, byteArrayOf(n.toByte()))
        return response[4] != 0.toByte()
    }

    suspend fun startPrint(): Boolean {
        val response = sendCommand(0x01, byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun endPrint(): Boolean {
        val response = sendCommand(0xF3.toByte(), byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun startPagePrint(): Boolean {
        val response = sendCommand(0x03, byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun endPagePrint(): Boolean {
        val response = sendCommand(0xE3.toByte(), byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun allowPrintClear(): Boolean {
        val response = sendCommand(0x20, byteArrayOf(1))
        return response[4] != 0.toByte()
    }

    suspend fun setDimension(width: Int, height: Int): Boolean {
        val data = ByteBuffer.allocate(4)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .array()
        val response = sendCommand(0x13, data)
        return response[4] != 0.toByte()
    }

    suspend fun setQuantity(n: Int): Boolean {
        val data = ByteBuffer.allocate(2).putShort(n.toShort()).array()
        val response = sendCommand(0x15, data)
        return response[4] != 0.toByte()
    }

    suspend fun getPrintStatus(): Map<String, Int> {
        val response = sendCommand(0xA3.toByte(), byteArrayOf(1))
        val data = response.copyOfRange(4, response.size - 3)
        return mapOf(
            "page" to ByteBuffer.wrap(data.copyOfRange(0, 2)).short.toInt(),
            "progress1" to (data[2].toInt() and 0xFF),
            "progress2" to (data[3].toInt() and 0xFF)
        )
    }

    suspend fun getInfo(key: Byte): Any {
        val response = sendCommand(0x40, byteArrayOf(key))
        val data = response.copyOfRange(4, response.size - 3)
        return when (key) {
            11.toByte() -> data.joinToString("") { "%02x".format(it) } // DEVICESERIAL
            9.toByte(), 12.toByte() -> ByteBuffer.wrap(data).int / 100.0 // SOFTVERSION, HARDVERSION
            else -> ByteBuffer.wrap(data).int
        }
    }

    suspend fun getRfid(): Map<String, Any>? {
        val response = sendCommand(0x1A, byteArrayOf(1))
        val data = response.copyOfRange(4, response.size - 3)

        if (data[0] == 0.toByte()) return null

        var idx = 8
        val barcodeLen = data[idx++].toInt()
        val barcode = String(data.copyOfRange(idx, idx + barcodeLen))
        idx += barcodeLen

        val serialLen = data[idx++].toInt()
        val serial = String(data.copyOfRange(idx, idx + serialLen))
        idx += serialLen

        val totalLen = ByteBuffer.wrap(data, idx, 2).short.toInt()
        val usedLen = ByteBuffer.wrap(data, idx + 2, 2).short.toInt()
        val type = data[idx + 4]

        return mapOf(
            "uuid" to data.copyOfRange(0, 8).joinToString("") { "%02x".format(it) },
            "barcode" to barcode,
            "serial" to serial,
            "used_len" to usedLen,
            "total_len" to totalLen,
            "type" to type
        )
    }

    suspend fun heartbeat(): Map<String, Int?> {
        val response = sendCommand(0xDC.toByte(), byteArrayOf(1))
        val data = response.copyOfRange(4, response.size - 3)

        return when (data.size) {
            20 -> mapOf(
                "closing_state" to null,
                "power_level" to null,
                "paper_state" to data[18].toInt(),
                "rfid_read_state" to data[19].toInt()
            )

            13 -> mapOf(
                "closing_state" to data[9].toInt(),
                "power_level" to data[10].toInt(),
                "paper_state" to data[11].toInt(),
                "rfid_read_state" to data[12].toInt()
            )

            19 -> mapOf(
                "closing_state" to data[15].toInt(),
                "power_level" to data[16].toInt(),
                "paper_state" to data[17].toInt(),
                "rfid_read_state" to data[18].toInt()
            )

            10 -> mapOf(
                "closing_state" to data[8].toInt(),
                "power_level" to data[9].toInt(),
                "paper_state" to null,
                "rfid_read_state" to data[8].toInt()
            )

            9 -> mapOf(
                "closing_state" to data[8].toInt(),
                "power_level" to null,
                "paper_state" to null,
                "rfid_read_state" to null
            )

            else -> mapOf(
                "closing_state" to null,
                "power_level" to null,
                "paper_state" to null,
                "rfid_read_state" to null
            )
        }
    }
}