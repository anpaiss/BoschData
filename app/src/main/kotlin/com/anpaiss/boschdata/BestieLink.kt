package com.anpaiss.boschdata

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * The Karoo as a Bluetooth central towards the BEStie board (nRF52840 running
 * our build of the BEStie firmware). The board is the accessory the bike
 * stays bonded with; it re-publishes the bike's LiveData over the BoschData
 * Service, plus its own status and log:
 *
 *  LIVE    the full LiveData protobuf of the bike at every message, decoded
 *          with the same [LiveData] parser as the direct link;
 *  STATUS  [BoardStatus], every 5 s and on every link change: bike up/down,
 *          counters, boots, last fault;
 *  LOG     one firmware log line per notification, written verbatim in our
 *          log under the tag BRD: the board's console lives in bosch_data.log.
 *
 * Finding the board: a scan filtered on the name "BEStie" (the board puts the
 * full name in every advertising packet), connect, MTU 247, discover, read
 * STATUS and LIVE, subscribe LOG, STATUS, LIVE in that order (the LOG ring is
 * replayed on subscribe, so the board's recent past arrives first). The link
 * is open: no bonding with the Karoo, the bike's bond is the board's business.
 *
 * Everything runs on the main looper: the stack's callbacks are posted there,
 * the GATT operations go through a queue one at a time (Android drops a second
 * operation issued before the first one answers).
 */
@SuppressLint("MissingPermission")
class BestieLink(
    private val ctx: Context,
    private val logger: BoschDataLogger,
    private val prefs: Prefs,
    private val listener: LdiLink.Listener,
) {
    companion object {
        val BDS_SERVICE: UUID = UUID.fromString("00000001-6b61-726f-6f00-616e70616973")
        val BDS_LIVE: UUID    = UUID.fromString("00000002-6b61-726f-6f00-616e70616973")
        val BDS_STATUS: UUID  = UUID.fromString("00000003-6b61-726f-6f00-616e70616973")
        val BDS_LOG: UUID     = UUID.fromString("00000004-6b61-726f-6f00-616e70616973")
        val CCCD: UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val BOARD_NAME = "BEStie"
        const val MTU = 247
        private const val TAG = "BEST "
        private const val TAG_BOARD = "BRD  "
        private const val TAG_STATUS = "STAT "
        private const val WATCHDOG_MS = 10_000L
        /** The board pushes STATUS every 5 s: this much silence means the link is dead even if Android has not noticed. */
        private const val SILENCE_MS = 60_000L
        private const val HEARTBEAT_MS = 5 * 60_000L
        private const val RESCAN_DELAY_MS = 2_000L
        /** Fast scanning after start and after FORGET BOARD, then the balanced mode. */
        private const val FAST_SCAN_MS = 3 * 60_000L
        private const val SCAN_REMINDER_MS = 10 * 60_000L
    }

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var started = false
    /** BDS found and the three subscriptions done. */
    @Volatile var linked = false
        private set
    /** What the board last said about the bike; drives the fields through the listener. */
    @Volatile var bikeUp = false
        private set
    @Volatile var board: BoardStatus? = null
        private set

    // Debug counters
    private var liveMsgs = 0L
    private var heartbeatLiveMsgs = 0L
    private var statusMsgs = 0L
    private var boardLines = 0L
    private var connectAttempts = 0
    /** Connections that ended before the subscriptions: the rescan backs off with them. */
    private var failedConnects = 0
    private var scanStarts = 0
    private var lastLiveMs = 0L
    private var lastStatusMs = 0L
    private var linkedSinceMs = 0L
    private var lastHeartbeatMs = 0L
    private var scanSinceMs = 0L
    private var lastScanReminderMs = 0L
    private var fastUntilMs = 0L
    private var silenceLogged = false
    private var lastStatusKey: List<Any>? = null

    // GATT operations one at a time
    private val ops = ArrayDeque<Pair<String, () -> Boolean>>()
    private var opInFlight: String? = null
    private var subscriptionsPending = 0

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { logger.log(TAG, "Bluetooth acceso: riparto"); main.post { startRadio() } }
                BluetoothAdapter.STATE_TURNING_OFF -> { logger.log(TAG, "Bluetooth spento"); main.post { stopRadio() } }
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            val now = System.currentTimeMillis()
            if (linked) {
                val silence = now - lastStatusMs
                if (silence > SILENCE_MS) {
                    logger.log(TAG, "Scheda muta da ${silence / 1000} s (nessuno STATUS): chiudo il link e ricerco")
                    closeGatt("silenzio")
                    main.postDelayed({ startScan() }, RESCAN_DELAY_MS)
                } else if (now - lastHeartbeatMs >= HEARTBEAT_MS) {
                    lastHeartbeatMs = now
                    logger.log(TAG, "Battito: scheda collegata da ${(now - linkedSinceMs) / 60_000} min, $heartbeatLiveMsgs LIVE negli ultimi " +
                        "${HEARTBEAT_MS / 60_000} min ($liveMsgs totali), $statusMsgs STATUS, $boardLines righe di log; " +
                        "bici ${if (bikeUp) "UP" else "down"}; ${board?.summary() ?: "nessuno status"}")
                    heartbeatLiveMsgs = 0L
                }
            } else if (scanning) {
                if (fastUntilMs != 0L && now >= fastUntilMs) {
                    fastUntilMs = 0L
                    logger.log(TAG, "Ricerca veloce finita senza scheda: passo alla ricerca bilanciata")
                    startScan()
                } else if (now - lastScanReminderMs >= SCAN_REMINDER_MS) {
                    lastScanReminderMs = now
                    logger.log(TAG, "Ancora in ricerca della scheda da ${(now - scanSinceMs) / 60_000} min (accesa? batteria? firmware caricato?)")
                }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        ctx.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        val a = manager.adapter
        logger.log(TAG, "Avvio client verso la scheda BEStie: adattatore '${a?.name}' ${if (a?.isEnabled == true) "acceso" else "SPENTO"}, " +
            "scheda nota ${prefs.boardAddress ?: "nessuna"}")
        fastUntilMs = System.currentTimeMillis() + FAST_SCAN_MS
        main.postDelayed(watchdog, WATCHDOG_MS)
        startRadio()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(watchdog)
        runCatching { ctx.unregisterReceiver(stateReceiver) }
        stopRadio()
        logger.log(TAG, "Client BEStie fermato ($liveMsgs LIVE, $statusMsgs STATUS, $boardLines righe di log in tutto)")
    }

    /** FORGET BOARD: drop the saved address and look for any BEStie again. */
    fun forgetBoard() {
        logger.log(TAG, "FORGET BOARD: era ${prefs.boardAddress ?: "nessuna"}")
        prefs.boardAddress = null
        closeGatt("forget")
        fastUntilMs = System.currentTimeMillis() + FAST_SCAN_MS
        if (started) startScan()
    }

    /** One line for the app screen. */
    fun status(): String = when {
        !started -> "link off"
        linked -> "board linked" + (board?.let { b -> ", bike ${if (b.bikeConnected) "UP" else "down"}, board batt ${b.boardBattPct ?: "?"} %, " +
            "up ${b.uptimeS / 60} min, boot #${b.boots}" + (if (b.hasFault) ", LAST FAULT ${b.faultText()}" else "") } ?: "")
        gatt != null -> "board found, connecting…"
        scanning -> "looking for the board '${BOARD_NAME}'${if (fastUntilMs != 0L) " (fast)" else ""}"
        manager.adapter?.isEnabled != true -> "Bluetooth off"
        else -> "idle"
    }

    private fun startRadio() {
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { logger.log(TAG, "Bluetooth spento o assente: client in attesa"); return }
        startScan()
    }

    private fun stopRadio() {
        stopScan()
        closeGatt("stop")
    }

    // ── Scan ────────────────────────────────────────────────────────────

    private fun startScan() {
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled || !started || gatt != null) return
        val s = adapter.bluetoothLeScanner ?: run { logger.log(TAG, "Nessuno scanner LE"); return }
        if (scanning) runCatching { s.stopScan(scanCallback) }
        scanner = s
        val fast = fastUntilMs != 0L && System.currentTimeMillis() < fastUntilMs
        val filters = mutableListOf(ScanFilter.Builder().setDeviceName(BOARD_NAME).build())
        prefs.boardAddress?.let { filters += ScanFilter.Builder().setDeviceAddress(it).build() }
        val settings = ScanSettings.Builder()
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .build()
        runCatching { s.startScan(filters, settings, scanCallback) }
            .onFailure { logger.logError(TAG, "startScan fallita", it); return }
        scanning = true
        scanStarts++
        scanSinceMs = System.currentTimeMillis()
        lastScanReminderMs = scanSinceMs
        logger.log(TAG, "Ricerca della scheda n. $scanStarts (${if (fast) "veloce" else "bilanciata"}, nome '$BOARD_NAME'" +
            (prefs.boardAddress?.let { " o indirizzo $it" } ?: "") + ")")
    }

    private fun stopScan() {
        if (scanning) runCatching { scanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { main.post { onFound(result) } }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.firstOrNull()?.let { r -> main.post { onFound(r) } } }
        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                logger.log(TAG, "Ricerca FALLITA, codice $errorCode (1 già avviata, 2 registrazione app, 3 interno, 4 non supportata, 5 risorse, 6 troppo frequente)")
                main.postDelayed({ startScan() }, 30_000L)
            }
        }
    }

    private fun onFound(r: ScanResult) {
        if (!started || gatt != null) return
        val dev = r.device
        logger.log(TAG, "Scheda trovata: ${dev.address} '${r.scanRecord?.deviceName ?: dev.name ?: ""}' rssi ${r.rssi} dBm, " +
            "servizi in advertising ${r.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().substring(4, 8) } ?: "-"}" +
            (if (dev.address != prefs.boardAddress && prefs.boardAddress != null) " (diversa da quella nota ${prefs.boardAddress})" else ""))
        stopScan()
        connect(dev)
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        connectAttempts++
        logger.log(TAG, "Connessione n. $connectAttempts a ${device.address}")
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { logger.log(TAG, "connectGatt ha restituito null"); main.postDelayed({ startScan() }, RESCAN_DELAY_MS) }
    }

    private fun closeGatt(why: String) {
        val g = gatt ?: return
        runCatching { g.disconnect() }
        runCatching { g.close() }
        gatt = null
        ops.clear(); opInFlight = null; subscriptionsPending = 0
        if (linked) {
            linked = false
            logger.log(TAG, "Link con la scheda chiuso ($why) dopo ${(System.currentTimeMillis() - linkedSinceMs) / 60_000} min, $liveMsgs LIVE in tutto")
            listener.onBoard(null)
        }
        setBikeUp(false, "scheda scollegata")
    }

    private fun setBikeUp(up: Boolean, why: String) {
        if (bikeUp == up) return
        bikeUp = up
        logger.log(TAG, "Bici ${if (up) "UP" else "DOWN"} secondo la scheda ($why)")
        listener.onLinkChanged(up)
    }

    private fun stateName(s: Int) = when (s) { 0 -> "disconnesso"; 1 -> "in connessione"; 2 -> "CONNESSO"; 3 -> "in disconnessione"; else -> "$s" }
    private fun statusName(s: Int) = when (s) {
        0 -> "ok"; 8 -> "timeout di supervisione (scheda spenta o fuori portata)"; 19 -> "chiusa dalla scheda"
        22 -> "chiusa dal Karoo"; 34 -> "LMP timeout"; 62 -> "connessione non stabilita"; 133 -> "errore GATT generico (riprovo)"
        else -> "status $s"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                logger.log(TAG, "Scheda ${g.device.address}: ${stateName(newState)}, ${statusName(status)}")
                if (g !== gatt) { runCatching { g.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(MTU)) logger.log(TAG, "requestMtu rifiutata: proseguo con la scoperta dei servizi").also { g.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasLinked = linked
                    closeGatt(statusName(status))
                    failedConnects = if (wasLinked) 0 else failedConnects + 1
                    val delay = RESCAN_DELAY_MS * minOf(failedConnects, 15).coerceAtLeast(1)
                    if (failedConnects >= 3) logger.log(TAG, "$failedConnects connessioni cadute di fila: ricerco fra ${delay / 1000} s")
                    if (started) main.postDelayed({ startScan() }, delay)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                logger.log(TAG, "MTU $mtu (status $status)" + if (mtu < 100) ": le righe di log arriveranno tagliate" else "")
                if (!g.discoverServices()) logger.log(TAG, "discoverServices rifiutata")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val addr = g.device.address
                logger.log(TAG, "Servizi di $addr (status $status): " + g.services.joinToString { it.uuid.toString().let { u -> if (u.endsWith("616e70616973")) "BDS:" + u.substring(4, 8) else u.substring(4, 8) } })
                val bds = g.getService(BDS_SERVICE)
                if (bds == null) {
                    logger.log(TAG, "$addr NON ha il BoschData Service: è una BEStie col firmware originale? Riprovo fra 60 s")
                    closeGatt("senza BDS")
                    main.postDelayed({ startScan() }, 60_000L)
                    return@post
                }
                val live = bds.getCharacteristic(BDS_LIVE); val st = bds.getCharacteristic(BDS_STATUS); val log = bds.getCharacteristic(BDS_LOG)
                if (live == null || st == null || log == null) {
                    logger.log(TAG, "BoschData Service incompleto (live ${live != null}, status ${st != null}, log ${log != null})")
                    closeGatt("BDS incompleto")
                    main.postDelayed({ startScan() }, 60_000L)
                    return@post
                }
                if (prefs.boardAddress != addr) { prefs.boardAddress = addr; logger.log(TAG, "Scheda $addr salvata") }
                subscriptionsPending = 3
                enqueue("lettura STATUS") { g.readCharacteristic(st) }
                enqueue("lettura LIVE") { g.readCharacteristic(live) }
                enqueue("notifiche LOG") { subscribe(g, log) }
                enqueue("notifiche STATUS") { subscribe(g, st) }
                enqueue("notifiche LIVE") { subscribe(g, live) }
            }
        }

        @Deprecated("Android 12 (API 32) still calls this signature")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val bytes = ch.value ?: byteArrayOf()
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) handle(ch.uuid, bytes, "lettura")
                else logger.log(TAG, "Lettura di ${name(ch.uuid)} fallita, status $status")
                opDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                val which = name(d.characteristic.uuid)
                if (status == BluetoothGatt.GATT_SUCCESS) logger.log(TAG, "Notifiche $which abilitate")
                else logger.log(TAG, "Abilitazione notifiche $which fallita, status $status")
                if (--subscriptionsPending <= 0 && !linked) {
                    linked = true
                    failedConnects = 0
                    linkedSinceMs = System.currentTimeMillis()
                    lastStatusMs = linkedSinceMs
                    lastHeartbeatMs = linkedSinceMs
                    heartbeatLiveMsgs = 0L
                    logger.log(TAG, "Scheda collegata: ${board?.summary() ?: "status non ancora letto"}")
                }
                opDone()
            }
        }

        @Deprecated("Android 12 (API 32) still calls this signature")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val bytes = ch.value ?: byteArrayOf()
            main.post { handle(ch.uuid, bytes, "notifica") }
        }
    }

    private fun name(u: UUID) = when (u) { BDS_LIVE -> "LIVE"; BDS_STATUS -> "STATUS"; BDS_LOG -> "LOG"; else -> u.toString().substring(4, 8) }

    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) { logger.log(TAG, "setCharacteristicNotification ${name(ch.uuid)} rifiutata"); return false }
        val cccd = ch.getDescriptor(CCCD) ?: run { logger.log(TAG, "${name(ch.uuid)} senza CCCD"); return false }
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        return g.writeDescriptor(cccd)
    }

    private fun enqueue(name: String, op: () -> Boolean) {
        ops.addLast(name to op)
        if (opInFlight == null) nextOp()
    }

    private fun nextOp() {
        val (name, op) = ops.removeFirstOrNull() ?: run { opInFlight = null; return }
        opInFlight = name
        if (!op()) {
            logger.log(TAG, "$name rifiutata dallo stack")
            if (name.startsWith("notifiche")) subscriptionsPending--
            nextOp()
        }
    }

    private fun opDone() { nextOp() }

    // ── Data ─────────────────────────────────────────────────────────────

    /** LDI fields not in V1.0: logged when first seen and at each change. */
    private val unknownFields = UnknownFieldLog()

    private fun handle(uuid: UUID, bytes: ByteArray, how: String) {
        val now = System.currentTimeMillis()
        when (uuid) {
            BDS_LIVE -> {
                if (bytes.isEmpty()) { if (how == "lettura") logger.log(TAG, "LIVE vuoto: la scheda non ha ancora dati dalla bici"); return }
                val d = runCatching { LiveData.decode(bytes) }
                    .onFailure { logger.log(TAG, "LIVE non decodificabile (${bytes.size} byte): ${it.message}") }
                    .getOrNull() ?: return
                liveMsgs++; heartbeatLiveMsgs++
                for (line in unknownFields.lines(d.unknown, System.currentTimeMillis())) logger.log(TAG, line)
                lastLiveMs = now
                if (liveMsgs == 1L || how == "lettura") logger.log(TAG, "LIVE ($how, ${bytes.size} byte): $d")
                // The read returns the board's last stored LIVE, also with the bike off: the
                // STATUS read just before says whether the bike is there
                if (how == "lettura" && board?.bikeConnected == false) {
                    logger.log(TAG, "LIVE letto con la bici giù: dato vecchio, non mostrato")
                    return
                }
                setBikeUp(true, "LIVE in arrivo")
                listener.onLiveData(d)
            }
            BDS_STATUS -> {
                val s = runCatching { BoardStatus.decode(bytes) }
                    .onFailure { logger.log(TAG, "STATUS non decodificabile (${bytes.size} byte): ${it.message}") }
                    .getOrNull() ?: return
                statusMsgs++
                lastStatusMs = now
                if (silenceLogged) { silenceLogged = false; logger.log(TAG, "STATUS ripresi") }
                val key = s.linkKey()
                if (key != lastStatusKey || how == "lettura") {
                    lastStatusKey = key
                    logger.log(TAG_STATUS, s.summary())
                }
                board = s
                listener.onBoard(s.boardBattPct)
                setBikeUp(s.bikeConnected, "STATUS")
            }
            BDS_LOG -> {
                boardLines++
                logger.log(TAG_BOARD, String(bytes, Charsets.UTF_8))
            }
            else -> logger.log(TAG, "Dati da ${name(uuid)}: ${bytes.size} byte")
        }
    }
}
