package com.tapcontactcard.hce

import android.content.Context
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.tapcontactcard.data.ContactRepository

/**
 * Host Card Emulation service implementing an NFC Forum Type 4 Tag.
 * Handles ISO 7816-4 APDU commands to beam vCard or URI NDEF payloads to any NFC reader.
 */
class ContactHceService : HostApduService() {

    enum class SelectedFile {
        NONE,
        CC,
        NDEF
    }

    private var selectedFile: SelectedFile = SelectedFile.NONE
    private var isAppSelected: Boolean = false
    private var ndefBuffer: ByteArray = ByteArray(0)
    private var hasTriggeredBeamForCurrentRead: Boolean = false

    private lateinit var repository: ContactRepository

    override fun onCreate() {
        super.onCreate()
        repository = ContactRepository(this)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_FILE_NOT_FOUND
        }

        // 1. SELECT NDEF Tag Application
        // 00 A4 04 00 07 D2 76 00 00 85 01 01 (optional Le = 00)
        if (isSelectAid(commandApdu)) {
            Log.d(TAG, "SELECT NDEF Tag Application matched")
            isAppSelected = true
            selectedFile = SelectedFile.NONE
            hasTriggeredBeamForCurrentRead = false
            return SW_SUCCESS
        }

        // If application not selected yet, reject
        if (!isAppSelected) {
            Log.w(TAG, "Command received before application selection")
            return SW_FILE_NOT_FOUND
        }

        // 2. SELECT Capability Container (CC)
        // 00 A4 00 0C 02 E1 03
        if (isSelectFile(commandApdu, FILE_ID_CC)) {
            Log.d(TAG, "SELECT CC matched")
            selectedFile = SelectedFile.CC
            return SW_SUCCESS
        }

        // 4. SELECT NDEF File
        // 00 A4 00 0C 02 E1 04
        if (isSelectFile(commandApdu, FILE_ID_NDEF)) {
            Log.d(TAG, "SELECT NDEF matched")
            selectedFile = SelectedFile.NDEF
            hasTriggeredBeamForCurrentRead = false

            // Build dynamic NDEF buffer with 2-byte big-endian NLEN
            val contactData = repository.getContactData()
            val ndefMessage = contactData.toNdefMessage()
            val msgBytes = ndefMessage.toByteArray()
            val nlen = msgBytes.size

            val buffer = ByteArray(2 + nlen)
            buffer[0] = ((nlen shr 8) and 0xFF).toByte()
            buffer[1] = (nlen and 0xFF).toByte()
            System.arraycopy(msgBytes, 0, buffer, 2, nlen)
            ndefBuffer = buffer

            Log.d(TAG, "NDEF payload prepared. Total size: ${ndefBuffer.size} bytes (NLEN=$nlen)")
            return SW_SUCCESS
        }

        // 3 & 5. READ BINARY
        // 00 B0 [offset_hi] [offset_lo] [le]
        if (isReadBinary(commandApdu)) {
            val offset = ((commandApdu[2].toInt() and 0xFF) shl 8) or (commandApdu[3].toInt() and 0xFF)
            val le = if (commandApdu.size >= 5) {
                val rawLe = commandApdu[4].toInt() and 0xFF
                if (rawLe == 0) 256 else rawLe
            } else {
                256
            }

            return when (selectedFile) {
                SelectedFile.CC -> {
                    handleReadBinary(CC_FILE_BYTES, offset, le)
                }
                SelectedFile.NDEF -> {
                    val response = handleReadBinary(ndefBuffer, offset, le)
                    // Check if complete NDEF payload was read
                    if (!hasTriggeredBeamForCurrentRead && ndefBuffer.isNotEmpty() && (offset + le >= ndefBuffer.size)) {
                        hasTriggeredBeamForCurrentRead = true
                        Log.i(TAG, "Complete NDEF file read! Triggering beam feedback.")
                        triggerBeamSuccess()
                    }
                    response
                }
                SelectedFile.NONE -> SW_FILE_NOT_FOUND
            }
        }

        // 6. Unknown / Unrecognized APDU
        return SW_FILE_NOT_FOUND
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "onDeactivated: reason=$reason")
        isAppSelected = false
        selectedFile = SelectedFile.NONE
        hasTriggeredBeamForCurrentRead = false
    }

    private fun handleReadBinary(source: ByteArray, offset: Int, le: Int): ByteArray {
        if (offset >= source.size) {
            return SW_WRONG_PARAMETERS
        }

        val available = source.size - offset
        val readLength = minOf(le, available)

        val result = ByteArray(readLength + 2)
        System.arraycopy(source, offset, result, 0, readLength)
        result[readLength] = 0x90.toByte()
        result[readLength + 1] = 0x00.toByte()
        return result
    }

    private fun triggerBeamSuccess() {
        // 1. Vibration
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }

        // 2. Broadcast event to UI
        try {
            val intent = Intent(ACTION_CONTACT_BEAMED).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed", e)
        }
    }

    companion object {
        const val TAG = "ContactHceService"
        const val ACTION_CONTACT_BEAMED = "com.tapcontactcard.ACTION_CONTACT_BEAMED"

        // Status words
        val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val SW_WRONG_PARAMETERS = byteArrayOf(0x6B.toByte(), 0x00.toByte())

        // File IDs
        val FILE_ID_CC = byteArrayOf(0xE1.toByte(), 0x03.toByte())
        val FILE_ID_NDEF = byteArrayOf(0xE1.toByte(), 0x04.toByte())

        // AID: D2760000850101
        val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x85.toByte(), 0x01.toByte(), 0x01.toByte()
        )

        // 15-byte Capability Container (CC) file
        val CC_FILE_BYTES = byteArrayOf(
            0x00.toByte(), 0x0F.toByte(),
            0x20.toByte(),
            0x00.toByte(), 0xFF.toByte(),
            0x00.toByte(), 0xFF.toByte(),
            0x04.toByte(), 0x06.toByte(),
            0xE1.toByte(), 0x04.toByte(),
            0x08.toByte(), 0x00.toByte(),
            0x00.toByte(),
            0xFF.toByte()
        )

        fun isSelectAid(apdu: ByteArray): Boolean {
            if (apdu.size < 12) return false
            if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte() ||
                apdu[2] != 0x04.toByte() || apdu[3] != 0x00.toByte()
            ) {
                return false
            }
            val lc = apdu[4].toInt() and 0xFF
            if (lc != 7 || apdu.size < 5 + lc) return false

            for (i in 0 until 7) {
                if (apdu[5 + i] != NDEF_AID[i]) return false
            }
            return true
        }

        fun isSelectFile(apdu: ByteArray, fileId: ByteArray): Boolean {
            if (apdu.size < 7) return false
            if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte() ||
                apdu[2] != 0x00.toByte() || apdu[3] != 0x0C.toByte() ||
                apdu[4] != 0x02.toByte()
            ) {
                return false
            }
            return apdu[5] == fileId[0] && apdu[6] == fileId[1]
        }

        fun isReadBinary(apdu: ByteArray): Boolean {
            if (apdu.size < 4) return false
            return apdu[0] == 0x00.toByte() && apdu[1] == 0xB0.toByte()
        }
    }
}
