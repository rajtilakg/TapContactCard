package com.tapcontactcard.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tapcontactcard.data.ContactData
import com.tapcontactcard.data.ContactRepository
import com.tapcontactcard.data.PayloadMode
import com.tapcontactcard.data.normalizeModeBUrl
import com.tapcontactcard.hce.ContactHceService
import com.tapcontactcard.ui.theme.ActiveGreen
import com.tapcontactcard.ui.theme.BorderDark
import com.tapcontactcard.ui.theme.DarkBackground
import com.tapcontactcard.ui.theme.DarkCardBg
import com.tapcontactcard.ui.theme.DarkSurface
import com.tapcontactcard.ui.theme.ErrorRed
import com.tapcontactcard.ui.theme.NeonCyan
import com.tapcontactcard.ui.theme.NeonPurple
import com.tapcontactcard.ui.theme.TapContactCardTheme
import com.tapcontactcard.ui.theme.TextPrimary
import com.tapcontactcard.ui.theme.TextSecondary
import com.tapcontactcard.util.QrCodeGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null
    private lateinit var repository: ContactRepository

    private val isBeamedEventTriggered = mutableStateOf(false)
    private val isNfcEnabledState = mutableStateOf(true)

    private val beamedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ContactHceService.ACTION_CONTACT_BEAMED) {
                isBeamedEventTriggered.value = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ContactRepository(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter != null) {
            cardEmulation = CardEmulation.getInstance(nfcAdapter)
        }

        setContent {
            TapContactCardTheme {
                MainScreen(
                    repository = repository,
                    isNfcSupported = nfcAdapter != null,
                    isNfcEnabled = isNfcEnabledState.value,
                    isBeamed = isBeamedEventTriggered.value,
                    onDismissBeamed = { isBeamedEventTriggered.value = false },
                    onOpenNfcSettings = {
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isNfcEnabledState.value = nfcAdapter?.isEnabled == true

        val filter = IntentFilter(ContactHceService.ACTION_CONTACT_BEAMED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                beamedReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(beamedReceiver, filter)
        }

        // Prioritize ContactHceService for incoming RF taps while in the foreground
        nfcAdapter?.let { adapter ->
            cardEmulation?.let { emulation ->
                try {
                    val serviceComponent = ComponentName(this, ContactHceService::class.java)
                    emulation.setPreferredService(this, serviceComponent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to setPreferredService", e)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(beamedReceiver)
        } catch (_: Exception) {}

        nfcAdapter?.let {
            cardEmulation?.let { emulation ->
                try {
                    emulation.unsetPreferredService(this)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to unsetPreferredService", e)
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    repository: ContactRepository,
    isNfcSupported: Boolean,
    isNfcEnabled: Boolean,
    isBeamed: Boolean,
    onDismissBeamed: () -> Unit,
    onOpenNfcSettings: () -> Unit
) {
    val initialData = remember { repository.getContactData() }
    var fullName by remember { mutableStateOf(initialData.fullName) }
    var phoneNumber by remember { mutableStateOf(initialData.phoneNumber) }
    var email by remember { mutableStateOf(initialData.email) }
    var customUrl by remember { mutableStateOf(initialData.customUrl) }
    var mode by remember { mutableStateOf(initialData.mode) }
    var showQrCode by remember { mutableStateOf(false) }
    var showIPhoneHelp by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val hasContactDetails = fullName.trim().isNotBlank() && phoneNumber.trim().isNotBlank()

    if (isBeamed) {
        DisposableEffect(Unit) {
            val job = scope.launch {
                delay(4000)
                onDismissBeamed()
            }
            onDispose { job.cancel() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Brush.linearGradient(listOf(NeonCyan, NeonPurple)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = "NFC Logo",
                                tint = DarkBackground,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "TapContactCard",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "Tap-to-Share Digital Business Card",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Active Indicator Banner (reflects configured status)
                ActiveHceStatusIndicator(
                    isNfcSupported = isNfcSupported,
                    isNfcEnabled = isNfcEnabled,
                    hasContactDetails = hasContactDetails,
                    onOpenNfcSettings = onOpenNfcSettings
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Live Contact Card Preview
                ContactCardPreview(
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    email = email,
                    customUrl = customUrl,
                    mode = mode
                )

                Spacer(modifier = Modifier.height(10.dp))

                // QR Code Action Toggle Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { showQrCode = !showQrCode },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showQrCode) "Hide QR Code" else "Show QR Code",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Expandable Offline vCard QR Code Card
                AnimatedVisibility(
                    visible = showQrCode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val vcardString = remember(fullName, phoneNumber, email) {
                        ContactData(
                            fullName = fullName,
                            phoneNumber = phoneNumber,
                            email = email
                        ).generateVCardString()
                    }
                    val qrBitmap = remember(vcardString) {
                        QrCodeGenerator.generateQrCode(vcardString, 512)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, BorderDark)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Offline vCard QR Code",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Scan with any smartphone camera to save contact",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (qrBitmap != null) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "vCard QR Code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    CircularProgressIndicator(color = NeonCyan)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Encodes full vCard 3.0 directly — no internet needed",
                                fontSize = 11.sp,
                                color = NeonCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Consumer-Friendly Mode Selector: Android Tap vs iPhone / Web Link
                PayloadModeSelector(
                    selectedMode = mode,
                    onModeSelected = { newMode ->
                        mode = newMode
                        val updated = ContactData(fullName, phoneNumber, email, customUrl, newMode)
                        repository.saveContactData(updated)
                    }
                )

                // Expandable Helper Tip when "iPhone / Web Link" is selected
                AnimatedVisibility(
                    visible = mode == PayloadMode.MODE_B_UNIVERSAL_URL,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showIPhoneHelp = !showIPhoneHelp },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = NeonPurple,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "How to set up an iPhone .vcf link?",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }
                                Icon(
                                    imageVector = if (showIPhoneHelp) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            if (showIPhoneHelp) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "1. Create a GitHub Gist with your .vcf contact card.\n" +
                                            "2. Click 'Raw' to copy the raw URL (gist.githubusercontent.com/...).\n" +
                                            "3. Paste the URL below. TapContactCard automatically routes it via gist.githack.com so Safari downloads it with the proper 'text/vcard' header and opens the native iOS contact sheet!",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Form Fields
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface, RoundedCornerShape(16.dp))
                        .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Contact Details",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("Full Name") },
                        placeholder = { Text("John Doe", color = TextSecondary.copy(alpha = 0.5f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, tint = NeonCyan)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = outlinedFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Phone Number") },
                        placeholder = { Text("+91 98765 43210", color = TextSecondary.copy(alpha = 0.5f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = NeonCyan)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next
                        ),
                        colors = outlinedFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email Address") },
                        placeholder = { Text("john@example.com", color = TextSecondary.copy(alpha = 0.5f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null, tint = NeonCyan)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        colors = outlinedFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Universal Profile / .vcf URL") },
                        placeholder = { Text("https://gist.github.com/.../contact.vcf", color = TextSecondary.copy(alpha = 0.5f)) },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null, tint = NeonPurple)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = outlinedFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save & Arm Button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val normalizedUrl = normalizeModeBUrl(customUrl)
                        customUrl = normalizedUrl

                        val updated = ContactData(
                            fullName = fullName,
                            phoneNumber = phoneNumber,
                            email = email,
                            customUrl = normalizedUrl,
                            mode = mode
                        )
                        repository.saveContactData(updated)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = if (hasContactDetails) "✓ Contact armed! Ready for tap transmission." else "✓ Details saved.",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DarkBackground
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Nfc,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save & Arm NFC",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Beamed Celebration Overlay
            AnimatedVisibility(
                visible = isBeamed,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                BeamedCelebrationCard(onDismiss = onDismissBeamed)
            }
        }
    }
}

@Composable
fun ActiveHceStatusIndicator(
    isNfcSupported: Boolean,
    isNfcEnabled: Boolean,
    hasContactDetails: Boolean,
    onOpenNfcSettings: () -> Unit
) {
    if (!isNfcSupported) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33EF4444), RoundedCornerShape(12.dp))
                .border(1.dp, ErrorRed, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed)
            Spacer(modifier = Modifier.width(8.dp))
            Text("NFC Hardware not supported on this device.", color = TextPrimary, fontSize = 13.sp)
        }
        return
    }

    if (!isNfcEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33F59E0B), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B))
                Spacer(modifier = Modifier.width(8.dp))
                Text("NFC is turned OFF in Android Settings", color = TextPrimary, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onOpenNfcSettings,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Turn On", color = NeonCyan, fontSize = 12.sp)
            }
        }
        return
    }

    if (!hasContactDetails) {
        // Subtle status when contact information is not yet entered
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x1A00E5FF), RoundedCornerShape(12.dp))
                .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Enter your contact details below to arm NFC",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
        return
    }

    // Fully configured & armed: Animated pulsing glowing indicator
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x1A10B981), RoundedCornerShape(12.dp))
            .border(1.dp, ActiveGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .scale(pulseScale)
                    .background(ActiveGreen.copy(alpha = pulseAlpha), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(ActiveGreen, CircleShape)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "Ready to Tap (Active)",
                color = ActiveGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "Hold back against another phone to beam",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun ContactCardPreview(
    fullName: String,
    phoneNumber: String,
    email: String,
    customUrl: String,
    mode: PayloadMode
) {
    val displayName = fullName.ifBlank { "Your Name" }
    val displayPhone = phoneNumber.ifBlank { "+1 (555) 000-0000" }
    val displayEmail = email.ifBlank { "yourname@example.com" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCardBg),
        border = BorderStroke(
            1.5.dp,
            Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.8f), NeonPurple.copy(alpha = 0.8f)))
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // Consumer-Friendly Mode Badge
                val badgeText = when (mode) {
                    PayloadMode.MODE_A_DIRECT_VCARD -> "Android Tap"
                    PayloadMode.MODE_B_UNIVERSAL_URL -> "iPhone Link"
                }
                val badgeColor = if (mode == PayloadMode.MODE_A_DIRECT_VCARD) NeonCyan else NeonPurple

                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Phone,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = displayPhone, color = TextSecondary, fontSize = 13.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = displayEmail, color = TextSecondary, fontSize = 13.sp)
                }

                if (mode == PayloadMode.MODE_B_UNIVERSAL_URL) {
                    val displayUrl = customUrl.ifBlank { "https://your-profile-url.com" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = NeonPurple,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = displayUrl, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    tint = NeonCyan.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun PayloadModeSelector(
    selectedMode: PayloadMode,
    onModeSelected: (PayloadMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(4.dp)
    ) {
        // Android Tap
        val isAndroid = selectedMode == PayloadMode.MODE_A_DIRECT_VCARD
        Box(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (isAndroid) NeonCyan else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .clickable { onModeSelected(PayloadMode.MODE_A_DIRECT_VCARD) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Android Tap",
                    color = if (isAndroid) DarkBackground else TextPrimary,
                    fontWeight = if (isAndroid) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
                Text(
                    text = "Offline contact pop-up",
                    color = if (isAndroid) DarkBackground.copy(alpha = 0.8f) else TextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        // iPhone / Web Link
        val isIPhone = selectedMode == PayloadMode.MODE_B_UNIVERSAL_URL
        Box(
            modifier = Modifier
                .weight(1f)
                .background(
                    if (isIPhone) NeonPurple else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .clickable { onModeSelected(PayloadMode.MODE_B_UNIVERSAL_URL) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "iPhone / Web Link",
                    color = if (isIPhone) TextPrimary else TextPrimary,
                    fontWeight = if (isIPhone) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
                Text(
                    text = "Universal URL or .vcf",
                    color = if (isIPhone) TextPrimary.copy(alpha = 0.8f) else TextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun BeamedCelebrationCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(2.dp, ActiveGreen),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(ActiveGreen.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = ActiveGreen,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "🎉 Contact Beamed!",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Another device successfully read your contact card via NFC.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ActiveGreen, contentColor = DarkBackground),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = BorderDark,
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = NeonCyan
)
