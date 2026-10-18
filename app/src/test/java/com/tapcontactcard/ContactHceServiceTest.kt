package com.tapcontactcard

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tapcontactcard.data.ContactData
import com.tapcontactcard.data.ContactRepository
import com.tapcontactcard.data.PayloadMode
import com.tapcontactcard.data.normalizeModeBUrl
import com.tapcontactcard.hce.ContactHceService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactHceServiceTest {

    @Test
    fun testSelectAidApduRecognition() {
        // Standard SELECT AID: 00 A4 04 00 07 D2 76 00 00 85 01 01 00
        val selectAidWithLe = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(),
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x85.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x00.toByte()
        )
        assertTrue("SELECT AID with Le should match", ContactHceService.isSelectAid(selectAidWithLe))

        // Without trailing Le
        val selectAidWithoutLe = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(),
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x85.toByte(), 0x01.toByte(), 0x01.toByte()
        )
        assertTrue("SELECT AID without Le should match", ContactHceService.isSelectAid(selectAidWithoutLe))

        // Incorrect AID
        val invalidAid = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            0x07.toByte(),
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x85.toByte(), 0x01.toByte(), 0x99.toByte()
        )
        assertFalse("Wrong AID should not match", ContactHceService.isSelectAid(invalidAid))
    }

    @Test
    fun testSelectCcAndNdefFiles() {
        val selectCc = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(),
            0x02.toByte(), 0xE1.toByte(), 0x03.toByte()
        )
        assertTrue("SELECT CC should match", ContactHceService.isSelectFile(selectCc, ContactHceService.FILE_ID_CC))
        assertFalse("SELECT CC should not match NDEF", ContactHceService.isSelectFile(selectCc, ContactHceService.FILE_ID_NDEF))

        val selectNdef = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(),
            0x02.toByte(), 0xE1.toByte(), 0x04.toByte()
        )
        assertTrue("SELECT NDEF should match", ContactHceService.isSelectFile(selectNdef, ContactHceService.FILE_ID_NDEF))
        assertFalse("SELECT NDEF should not match CC", ContactHceService.isSelectFile(selectNdef, ContactHceService.FILE_ID_CC))
    }

    @Test
    fun testReadBinaryRecognition() {
        val readBinary = byteArrayOf(0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0F.toByte())
        assertTrue("READ BINARY should match", ContactHceService.isReadBinary(readBinary))

        val notReadBinary = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x00.toByte())
        assertFalse("Other command should not match READ BINARY", ContactHceService.isReadBinary(notReadBinary))
    }

    @Test
    fun testCapabilityContainerFileContents() {
        // Must be exactly 15 bytes:
        // 00 0F 20 00 FF 00 FF 04 06 E1 04 08 00 00 FF
        val expected = byteArrayOf(
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
        assertEquals(15, ContactHceService.CC_FILE_BYTES.size)
        assertArrayEquals(expected, ContactHceService.CC_FILE_BYTES)
    }

    @Test
    fun testVCardGenerationFormat() {
        val contact = ContactData(
            fullName = "John Doe",
            phoneNumber = "+1234567890",
            email = "john.doe@example.com",
            customUrl = "https://example.com/john",
            mode = PayloadMode.MODE_A_DIRECT_VCARD
        )

        val vcard = contact.generateVCardString()
        assertTrue("vCard must start with BEGIN:VCARD", vcard.contains("BEGIN:VCARD"))
        assertTrue("vCard must have VERSION:3.0", vcard.contains("VERSION:3.0"))
        assertTrue("vCard must have N:Doe;John;;;", vcard.contains("N:Doe;John;;;"))
        assertTrue("vCard must have FN:John Doe", vcard.contains("FN:John Doe"))
        assertTrue("vCard must have TEL;TYPE=CELL:+1234567890", vcard.contains("TEL;TYPE=CELL:+1234567890"))
        assertTrue("vCard must have EMAIL:john.doe@example.com", vcard.contains("EMAIL:john.doe@example.com"))
        assertTrue("vCard must end with END:VCARD", vcard.contains("END:VCARD"))
    }

    @Test
    fun testSplitNameSingleAndMultiPart() {
        val single = ContactData(fullName = "Cher")
        assertEquals(Pair("Cher", ""), single.getSplitName())

        val twoPart = ContactData(fullName = "Jane Smith")
        assertEquals(Pair("Jane", "Smith"), twoPart.getSplitName())

        val threePart = ContactData(fullName = "Alexander Graham Bell")
        assertEquals(Pair("Alexander Graham", "Bell"), threePart.getSplitName())
    }

    @Test
    fun testNormalizeModeBUrl() {
        val rawGist = "https://gist.githubusercontent.com/user/12345/raw/67890/contact.vcf"
        val expectedGithack = "https://gist.githack.com/user/12345/raw/67890/contact.vcf"
        assertEquals(expectedGithack, normalizeModeBUrl(rawGist))

        val withoutScheme = "gist.githubusercontent.com/user/raw/card.vcf"
        assertEquals("https://gist.githack.com/user/raw/card.vcf", normalizeModeBUrl(withoutScheme))

        assertEquals(ContactRepository.DEFAULT_URL, normalizeModeBUrl(""))

        val normalUrl = "https://example.com/me.vcf"
        assertEquals(normalUrl, normalizeModeBUrl(normalUrl))
    }

    @Test
    fun testZxingQrCodeEncoding() {
        val contact = ContactData(
            fullName = "Alex Morgan",
            phoneNumber = "+1 555-0199",
            email = "alex@example.com"
        )
        val vcard = contact.generateVCardString()
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(vcard, BarcodeFormat.QR_CODE, 512, 512)
        assertNotNull(bitMatrix)
        assertEquals(512, bitMatrix.width)
        assertEquals(512, bitMatrix.height)
    }
}
