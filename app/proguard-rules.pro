# Proguard & R8 Rules for TapContactCard

# Preserve HostApduService class and key NFC callbacks
-keep public class com.tapcontactcard.hce.ContactHceService extends android.nfc.cardemulation.HostApduService {
    public <init>();
    public byte[] processCommandApdu(byte[], android.os.Bundle);
    public void onDeactivated(int);
}

# Preserve ZXing barcode generation engine
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
