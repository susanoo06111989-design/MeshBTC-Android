package com.meshbtc.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MeshScreen() } }
}

@Composable
fun MeshScreen() {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Escaneo detenido") }
    val peers = remember { mutableStateListOf<String>() }
    val manager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val id = result.device.address.takeLast(5) + " · RSSI " + result.rssi
                if (peers.none { it.startsWith(result.device.address.takeLast(5)) }) peers.add(id)
            }
            override fun onScanFailed(errorCode: Int) { status = "Error BLE: $errorCode" }
        }
    }
    fun startScan() {
        try {
            if (manager.adapter?.isEnabled != true) { status = "Activa Bluetooth"; return }
            val scanner = manager.adapter?.bluetoothLeScanner
            if (scanner == null) { status = "Escáner BLE no disponible"; return }
            peers.clear()
            scanner.startScan(callback)
            status = "Buscando dispositivos BLE cercanos"
        } catch (_: SecurityException) { status = "Faltan permisos Bluetooth" }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) startScan() else status = "Permisos denegados"
    }
    DisposableEffect(Unit) {
        onDispose {
            try { manager.adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: SecurityException) {}
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = { Surface { Text("MeshBTC · Alpha 1", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) } },
            bottomBar = { NavigationBar { listOf("Chats", "Malla", "Bitcoin", "Ajustes").forEachIndexed { index, name -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("✉", "◉", "₿", "⚙")[index]) }, label = { Text(name) }) } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (tab) {
                    0 -> { Text("Mensajería", style = MaterialTheme.typography.headlineSmall); Text("La mensajería entre teléfonos se incorporará en Alpha 2. No se simulan mensajes ni conexiones.") }
                    1 -> {
                        Text("Dispositivos BLE", style = MaterialTheme.typography.headlineSmall)
                        Text(status)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startScan() else permissionLauncher.launch(permissions)
                            }) { Text("Buscar") }
                            OutlinedButton(onClick = { try { manager.adapter?.bluetoothLeScanner?.stopScan(callback); status = "Escaneo detenido" } catch (_: SecurityException) { status = "Sin permisos" } }) { Text("Detener") }
                        }
                        Text("Detectados: ${peers.size} (no significa que tengan MeshBTC)")
                        LazyColumn { items(peers) { Text(it, Modifier.padding(8.dp)) } }
                    }
                    2 -> {
                        Text("Recibir Bitcoin", style = MaterialTheme.typography.headlineSmall)
                        Text("Solo recepción. Introduce una dirección propia; no introduzcas claves privadas.")
                        OutlinedTextField(value = address, onValueChange = { address = it.trim() }, label = { Text("Dirección Bitcoin") }, modifier = Modifier.fillMaxWidth())
                        val valid = address.matches(Regex("^(bc1[ac-hj-np-z02-9]{11,87}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$", RegexOption.IGNORE_CASE))
                        Text(if (valid) "Formato plausible (NO verificado criptográficamente)" else "Introduce una dirección mainnet válida")
                        Button(enabled = valid, onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Bitcoin address", address))
                        }) { Text("Copiar dirección") }
                        HorizontalDivider()
                        Text("Lightning: integración pendiente. No hay pagos activos ni confirmaciones en esta versión.")
                    }
                    else -> { Text("Acerca de MeshBTC"); Text("Alpha 1: descubrimiento BLE experimental y pantalla Bitcoin de solo recepción. Sin chats, malla multi-salto, QR ni Lightning operativos.") }
                }
            }
        }
    }
}
