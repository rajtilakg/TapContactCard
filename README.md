# TapContactCard

Turn your Android phone into a tap-to-share digital business card using Host Card Emulation (HCE) — no physical NFC tag or sticker required.

## Why I Built This
Ever since Android Beam was retired, sharing a phone number between two phones has felt unnecessarily clunky. Companies sell plastic NFC business cards and stickers that you slap onto the back of your phone, which always felt redundant to me — my phone *already* has an NFC controller built into it. Why buy a plastic NFC tag when the phone itself can emulate one?

I dug into Android's `HostApduService` and the ISO 7816-4 / NFC Forum Type 4 Tag specification to make my phone act like a passive NFC smart tag. Along the way, I ran into three real-world headaches and built this app to solve them:

1. **Phone-to-Phone NFC Collisions:** When two phones touch, payment wallets and default tag readers often fight each other. TapContactCard uses `CardEmulation.setPreferredService()` while open in the foreground so 100% of incoming RF reads go straight to your contact card.
2. **Android vs. iPhone Quirks:** Android phones natively open raw `text/vcard` MIME payloads straight into the Contacts app offline, while iOS locks background tag reading strictly to web URLs (`NDEF URI`). Worse, if you host a `.vcf` file on a raw GitHub Gist, GitHub serves it with `Content-Type: text/plain`, causing mobile browsers to just print raw text instead of importing the contact.
3. **Phones Without NFC:** Not everyone keeps NFC turned on, and some phones skip NFC hardware altogether.

## How It Works (3 Ways to Share)
- **Android Tap Mode (Default):** Emulates an NFC Type 4 Tag serving a raw `vCard 3.0` (`text/vcard`) payload over APDU commands. When any NFC-enabled Android phone taps yours, their Contacts app opens immediately with your Name, Number, and Email pre-filled — completely offline.
- **iPhone / Web Link Mode:** Emulates an NDEF URI record for iPhones (or custom profile links). If you paste a raw GitHub Gist `.vcf` link, the app automatically routes it through `gist.githack.com` so Safari receives the proper `Content-Type: text/vcard` header and opens the iOS "Add to Contacts" sheet directly.
- **Instant Offline QR Code:** One tap reveals a high-contrast 512×512 `vCard 3.0` QR code generated on-device with ZXing. Any iPhone or Android camera can scan it offline to save your contact.

## Under the Hood
- **100% Kotlin & Jetpack Compose (Material 3)**
- **Full ISO 7816-4 Type 4 Tag State Machine:** Handles `SELECT AID` (`D2760000850101`), `SELECT CC` (`E103`), `READ BINARY CC`, `SELECT NDEF` (`E104`), and multi-chunk `READ BINARY NDEF` frames with a 2-byte big-endian `NLEN` header.
- **Zero Internet Permissions:** The app does not request `android.permission.INTERNET`. All contact details stay locally on your device in `SharedPreferences`.

## Building from Source
1. Clone the repository:
   ```bash
   git clone https://github.com/rajtilakg/TapContactCard.git
   ```
2. Build the shrunk release APK:
   ```bash
   ./gradlew assembleRelease
   ```
   Or install directly to a connected Android 8.0+ device:
   ```bash
   ./gradlew installRelease
   ```

## License
MIT
