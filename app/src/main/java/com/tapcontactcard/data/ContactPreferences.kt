package com.tapcontactcard.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.charset.StandardCharsets

enum class PayloadMode {
    MODE_A_DIRECT_VCARD,
    MODE_B_UNIVERSAL_URL
}

/**
 * Normalizes raw GitHub Gist URLs from gist.githubusercontent.com to gist.githack.com
 * so that mobile browsers receive 'Content-Type: text/vcard' instead of 'text/plain'.
 */
fun normalizeModeBUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
    return withScheme.replace("gist.githubusercontent.com", "gist.githack.com", ignoreCase = true)
}

data class ContactData(
    val fullName: String = "",
    val phoneNumber: String = "",
    val email: String = "",
    val customUrl: String = "",
    val mode: PayloadMode = PayloadMode.MODE_A_DIRECT_VCARD
) {
    fun hasRequiredDetails(): Boolean {
        return fullName.trim().isNotBlank() && phoneNumber.trim().isNotBlank()
    }

    fun getSplitName(): Pair<String, String> {
        val trimmed = fullName.trim()
        if (trimmed.isEmpty()) return Pair("", "")
        val parts = trimmed.split("\\s+".toRegex())
        return if (parts.size == 1) {
            Pair(parts[0], "")
        } else {
            val first = parts.dropLast(1).joinToString(" ")
            val last = parts.last()
            Pair(first, last)
        }
    }

    fun generateVCardString(): String {
        val (first, last) = getSplitName()
        return "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "N:$last;$first;;;\r\n" +
                "FN:$fullName\r\n" +
                "TEL;TYPE=CELL:$phoneNumber\r\n" +
                "EMAIL:$email\r\n" +
                "END:VCARD\r\n"
    }

    fun toNdefMessage(): NdefMessage {
        return when (mode) {
            PayloadMode.MODE_A_DIRECT_VCARD -> {
                val vcardString = generateVCardString()
                val vcardBytes = vcardString.toByteArray(StandardCharsets.UTF_8)
                val mimeRecord = NdefRecord.createMime("text/vcard", vcardBytes)
                NdefMessage(arrayOf(mimeRecord))
            }
            PayloadMode.MODE_B_UNIVERSAL_URL -> {
                val targetUrl = normalizeModeBUrl(customUrl).ifBlank { "https://example.com" }
                val uriRecord = NdefRecord.createUri(Uri.parse(targetUrl))
                NdefMessage(arrayOf(uriRecord))
            }
        }
    }
}

class ContactRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getContactData(): ContactData {
        val fullName = prefs.getString(KEY_FULL_NAME, DEFAULT_FULL_NAME) ?: DEFAULT_FULL_NAME
        val phone = prefs.getString(KEY_PHONE, DEFAULT_PHONE) ?: DEFAULT_PHONE
        val email = prefs.getString(KEY_EMAIL, DEFAULT_EMAIL) ?: DEFAULT_EMAIL
        val rawUrl = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        val url = normalizeModeBUrl(rawUrl)

        val modeName = prefs.getString(KEY_MODE, PayloadMode.MODE_A_DIRECT_VCARD.name)
        val mode = try {
            PayloadMode.valueOf(modeName ?: PayloadMode.MODE_A_DIRECT_VCARD.name)
        } catch (_: Exception) {
            PayloadMode.MODE_A_DIRECT_VCARD
        }

        return ContactData(
            fullName = fullName,
            phoneNumber = phone,
            email = email,
            customUrl = url,
            mode = mode
        )
    }

    fun saveContactData(data: ContactData) {
        val normalizedUrl = normalizeModeBUrl(data.customUrl)
        prefs.edit().apply {
            putString(KEY_FULL_NAME, data.fullName)
            putString(KEY_PHONE, data.phoneNumber)
            putString(KEY_EMAIL, data.email)
            putString(KEY_URL, normalizedUrl)
            putString(KEY_MODE, data.mode.name)
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "tap_contact_card_prefs"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_PHONE = "phone_number"
        private const val KEY_EMAIL = "email"
        private const val KEY_URL = "custom_url"
        private const val KEY_MODE = "payload_mode"

        const val DEFAULT_FULL_NAME = ""
        const val DEFAULT_PHONE = ""
        const val DEFAULT_EMAIL = ""
        const val DEFAULT_URL = ""
    }
}
