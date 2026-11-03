package com.peakwatch.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// المرحلة 1: استماع فقط.
// هذا التطبيق لا يكتب أي أمر على الساعة. الكتابة الوحيدة هي تفعيل الاشتراك
// في الإشعارات (CCCD)، وهي إجراء BLE قياسي وليست أمرًا للساعة.
const val WATCH_MAC = "C0:25:AB:69:43:19"
val SERVICE_UART: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
val CHAR_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class BleListener(private val context: Context) {
    val log = mutableStateListOf<String>()
    var statusText by mutableStateOf("غير متصل")
        private set

    private var gatt: BluetoothGatt? = null
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(msg: String) {
        main.post { log.add("${fmt.format(Date())}  $msg") }
    }

    private fun setStatus(s: String) {
        main.post { statusText = s }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private fun props(p: Int): String {
        val l = mutableListOf<String>()
        if (p and 0x02 != 0) l += "READ"
        if (p and 0x04 != 0) l += "WRITE_NO_RESP"
        if (p and 0x08 != 0) l += "WRITE"
        if (p and 0x10 != 0) l += "NOTIFY"
        if (p and 0x20 != 0) l += "INDICATE"
        return l.joinToString("|")
    }

    fun connect() {
        if (gatt != null) {
            add("هناك اتصال قائم أو قيد المحاولة")
            return
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth مغلق")
            return
        }
        setStatus("جارٍ الاتصال...")
        add("اتصال بـ $WATCH_MAC")
        val device = adapter.getRemoteDevice(WATCH_MAC)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        setStatus("غير متصل")
    }

    private fun handleNotify(value: ByteArray) {
        add("◀ ${value.size}B: ${hex(value)}")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                add("اتصل (status=$st). جارٍ اكتشاف الخدمات")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                add("انقطع الاتصال (status=$st)")
                g.close()
                gatt = null
                setStatus("غير متصل")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            add("الخدمات (status=$st):")
            for (s in g.services) {
                add("  خدمة ${s.uuid}")
                for (c in s.characteristics) {
                    add("    خاصية ${c.uuid} [${props(c.properties)}]")
                }
            }
            val ch = g.getService(SERVICE_UART)?.getCharacteristic(CHAR_NOTIFY)
            if (ch == null) {
                add("لم أجد 6E400003. توقف.")
                setStatus("لم تُوجد الخاصية")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd == null) {
                add("لا يوجد وصف CCCD. توقف.")
                return
            }
            add("تفعيل الاشتراك في الإشعارات (استماع فقط)")
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            add("نتيجة الاشتراك: status=$st")
            if (st == 0) setStatus("متصل: يستمع فقط")
        }

        // Android 13 فما فوق
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(value)
        }

        // Android 12 وأقل
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { handleNotify(it) }
        }
    }
}

@Composable
fun Screen(ble: BleListener, requestConnect: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("PeakWatch: اختبار الاستماع", style = MaterialTheme.typography.titleMedium)
        Text(ble.statusText)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = requestConnect) { Text("اتصال") }
            OutlinedButton(onClick = { ble.disconnect() }) { Text("قطع") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(ble.log.joinToString("\n")))
            }) { Text("نسخ السجل") }
            OutlinedButton(onClick = { ble.log.clear() }) { Text("مسح") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(ble.log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ble = remember { BleListener(applicationContext) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { ok ->
                    if (ok) ble.connect() else ble.add("لم يُمنح إذن الأجهزة القريبة")
                }
                DisposableEffect(Unit) { onDispose { ble.disconnect() } }
                Screen(ble) {
                    val needs = Build.VERSION.SDK_INT >= 31 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needs) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) else ble.connect()
                }
            }
        }
    }
}
