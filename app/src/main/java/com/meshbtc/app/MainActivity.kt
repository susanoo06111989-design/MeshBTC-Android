package com.meshbtc.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.UUID

private const val TEST_MANUFACTURER_ID = 0xFFFF
private const val MAX_MESSAGE_BYTES = 14

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeshScreen() }
    }
}

@Composable
fun MeshScreen() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val localId = remember { UUID.randomUUID().toString().take(4) }
    var tab by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Bluetooth detenido") }
    var scanning by remember { mutableStateOf(false) }
    var advertising by remember { mutableStateOf(false) }
    var scanEvents by remember { mutableIntStateOf(0) }
    var meshEvents by remember { mutableIntStateOf(0) }
    var txState by remember { mutableStateOf("Sin transmisión") }
    val peers = remember { mutableStateListOf<String>() }
    val messages = remember { mutableStateListOf<String>() }
    val seen = remember { mutableStateListOf<String>() }

    val advertiseCallback = remember {
        object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                advertising = false
                txState = "Error de transmisión BLE: $errorCode"
                status = txState
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                advertising = true
                txState = "Anuncio BLE iniciado; entrega no confirmada"
                status = txState
            }
        }
    }
    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanEvents++
                val suffix = result.device.address.takeLast(5)
                val peer = "$suffix · RSSI ${result.rssi}"
                if (peers.none { it.startsWith(suffix) }) peers.add(peer)
                val bytes = result.scanRecord?.getManufacturerSpecificData(TEST_MANUFACTURER_ID)
                if (bytes != null && bytes.size >= 3) {
                    meshEvents++
                    val sender = "%02x%02x".format(bytes[0].toInt() and 255, bytes[1].toInt() and 255)
                    val msg = bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_8)
                    val key = "$sender:$msg"
                    if (key !in seen) {
                        seen.add(key)
                        messages.add("Recibido [$sender]: $msg")
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                scanning = false
                status = "Error al buscar: $errorCode"
            }
        }
    }

    fun startScan() {
        try {
            val adapter = manager.adapter
            if (adapter?.isEnabled != true) { status = "Activa Bluetooth"; return }
            val scanner = adapter.bluetoothLeScanner ?: run { status = "BLE no disponible"; return }
            if (!scanning) {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, scanCallback)
                scanning = true
                status = "Escuchando mensajes BLE cercanos"
            }
        } catch (_: SecurityException) { status = "Faltan permisos Bluetooth" }
    }
    fun sendMessage() {
        val bytes = draft.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) {
            status = "Máximo 14 bytes UTF-8 por mensaje"
            return
        }
        try {
            val adapter = manager.adapter
            if (adapter?.isEnabled != true) { status = "Activa Bluetooth"; return }
            val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                status = "Este teléfono no admite anuncios BLE"; return
            }
            advertiser.stopAdvertising(advertiseCallback)
            advertising = false
            txState = "Iniciando anuncio BLE..."
            val id = localId.chunked(2).map { it.toInt(16).toByte() }
            val payload = byteArrayOf(id[0], id[1]) + bytes
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(TEST_MANUFACTURER_ID, payload)
                .build()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            advertiser.startAdvertising(settings, data, advertiseCallback)
            messages.add("Intento de envío [$localId]: $draft")
            draft = ""
            status = "Intento de transmisión; esperando confirmación del radio BLE"
        } catch (_: SecurityException) { status = "Faltan permisos Bluetooth" }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) startScan() else status = "Permisos Bluetooth denegados"
    }
    fun requestAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            startScan()
        else launcher.launch(permissions)
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (_: SecurityException) {}
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = { Surface { Text("MeshBTC · Alpha 2", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) } },
            bottomBar = {
                NavigationBar {
                    listOf("Chats", "Malla", "Bitcoin", "Ajustes").forEachIndexed { index, name ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(listOf("✉", "◉", "₿", "⚙")[index]) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> {
                        Text("Chat BLE experimental", style = MaterialTheme.typography.headlineSmall)
                        Text("ID temporal: $localId · Sin cifrado ni confirmación de entrega")
                        Text(status)
                        Text("Diagnóstico: escaneos=$scanEvents · anuncios MeshBTC=$meshEvents")
                        Text("Radio: $txState")
                        Button(onClick = { requestAndScan() }) { Text(if (scanning) "Escuchando" else "Activar recepción") }
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it },
                            label = { Text("Mensaje corto (máx. 14 bytes)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${draft.toByteArray(Charsets.UTF_8).size}/14 bytes")
                        Button(
                            enabled = draft.isNotEmpty() && draft.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES,
                            onClick = { sendMessage() }
                        ) { Text("Transmitir por Bluetooth") }
                        Text("Cada envío sustituye el anuncio anterior. No envíes datos privados.")
                        LazyColumn { items(messages) { Text(it, Modifier.padding(vertical = 6.dp)) } }
                    }
                    1 -> {
                        Text("Dispositivos Bluetooth LE", style = MaterialTheme.typography.headlineSmall)
                        Text(status)
                        Text("Eventos BLE: $scanEvents · paquetes MeshBTC: $meshEvents")
                        Text("Radio transmisor: $txState")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { requestAndScan() }) { Text("Buscar") }
                            OutlinedButton(onClick = {
                                try {
                                    manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
                                    scanning = false
                                    status = "Escaneo detenido"
                                } catch (_: SecurityException) { status = "Sin permisos" }
                            }) { Text("Detener") }
                        }
                        Text("Detectados: ${peers.size} · No todos utilizan MeshBTC")
                        Text("Si eventos BLE=0, revisa permisos, Bluetooth y visibilidad de otros dispositivos.")
                        LazyColumn { items(peers) { Text(it, Modifier.padding(8.dp)) } }
                    }
                    2 -> {
                        Text("Recibir Bitcoin", style = MaterialTheme.typography.headlineSmall)
                        Text("Solo recepción. Nunca introduzcas claves privadas.")
                        OutlinedTextField(
                            value = address, onValueChange = { address = it.trim() },
                            label = { Text("Dirección Bitcoin") }, modifier = Modifier.fillMaxWidth()
                        )
                        val valid = address.matches(Regex("^(bc1[ac-hj-np-z02-9]{11,87}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$", RegexOption.IGNORE_CASE))
                        Text(if (valid) "Formato plausible; checksum NO verificado" else "Introduce una dirección mainnet")
                        Button(enabled = valid, onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Bitcoin address", address))
                        }) { Text("Copiar dirección") }
                        HorizontalDivider()
                        Text("Lightning pendiente. No hay pagos ni confirmaciones activas.")
                    }
                    else -> {
                        Text("MeshBTC Alpha 2", style = MaterialTheme.typography.headlineSmall)
                        Text("Prueba de anuncios BLE de texto corto. No hay cifrado, conexión persistente, mensajes largos, retransmisión multisalto ni entrega garantizada.")
                        Text("La identificación del fabricante 0xFFFF se usa únicamente para pruebas; no es apta para producción.")
                        Text("Diagnóstico: escaneos=$scanEvents, paquetes MeshBTC=$meshEvents, transmisión=$txState")
                    }
                }
            }
        }
    }
}
