package com.anpaiss.boschdata

import android.content.pm.PackageManager
import android.os.Build
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnManufacturerInfo
import io.hammerhead.karooext.models.WriteToRecordMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * BoschData: the Bosch Smart System e-bike on the Karoo, over the Bosch Live
 * Data Interface (LDI V1.0, May 2026). Two ways to the bike, chosen in the
 * app: through the BEStie board, the accessory the bike stays bonded with,
 * read by the Karoo as a central ([BestieLink], the default); or directly,
 * the Karoo itself as the LDI accessory ([LdiLink], which Android's address
 * rotation breaks at every restart). What the bike sends ([LiveData]) becomes:
 *
 *  - six data fields ([BikeFieldDataType]): battery, rider power, speed,
 *    cadence, odometer, light. Their streams carry the number: boschRange
 *    reads the battery from `TYPE_EXT::bosch-data::bike_battery`;
 *  - a Karoo sensor "Bosch eBike" ([startScan]/[connectDevice]) with power
 *    (the torque sensor), optionally cadence and speed: paired once from the
 *    Karoo sensors menu, the values go in the FIT file like any sensor;
 *  - the battery % in the FIT file of every ride ([startFit]), a developer
 *    field `bosch_battery`: the discharge curve of every ride, point by point.
 */
class BoschDataExtension : KarooExtension("bosch-data", BuildConfig.VERSION_NAME) {

    private lateinit var logger: BoschDataLogger
    private lateinit var prefs: Prefs
    private var ldi: LdiLink? = null
    private var bestie: BestieLink? = null
    private var karooSystem: KarooSystemService? = null
    private val consumerIds = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val bikeState = MutableStateFlow(BikeState())
    private val visibleDataTypeIds = MutableStateFlow<Set<String>?>(null)

    companion object {
        @Volatile var instance: BoschDataExtension? = null
        val BLUETOOTH_PERMISSIONS = arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_SCAN")
        const val DEVICE_UID = "bike"
        private const val SENSOR_PERIOD_MS = 1_000L     // "typical sensors emit at 1 Hz, even if the value is unchanged"
        private const val FIT_PERIOD_MS    = 5_000L
        /** FIT base type UINT8 = 2 (Garmin FIT SDK). Field 0 of this extension. */
        private val FIT_BATTERY = DeveloperField(fieldDefinitionNumber = 0, fitBaseTypeId = 2, fieldName = "bosch_battery", units = "%")
    }

    override val types: List<DataTypeImpl> by lazy { listOf(
        BikeFieldDataType(extension, "bike_battery", BikeFieldDataType.Kind.BATTERY, bikeState, visibleDataTypeIds),
        BikeFieldDataType(extension, "bike_power", BikeFieldDataType.Kind.POWER, bikeState, visibleDataTypeIds),
        BikeFieldDataType(extension, "bike_speed", BikeFieldDataType.Kind.SPEED, bikeState, visibleDataTypeIds),
        BikeFieldDataType(extension, "bike_cadence", BikeFieldDataType.Kind.CADENCE, bikeState, visibleDataTypeIds),
        BikeFieldDataType(extension, "bike_odometer", BikeFieldDataType.Kind.ODOMETER, bikeState, visibleDataTypeIds),
        BikeFieldDataType(extension, "bike_light", BikeFieldDataType.Kind.LIGHT, bikeState, visibleDataTypeIds),
    ) }

    override fun onCreate() {
        super.onCreate()
        logger = BoschDataLogger(applicationContext)
        BoschDataLogger.shared = logger
        prefs = Prefs(applicationContext)
        instance = this
        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            logger.log("SYS  ", "Versione ${pi.versionName} (${pi.longVersionCode}); fonte ${if (prefs.viaBoard) "scheda BEStie" else "bici diretta"}; sensore: potenza ${prefs.sensorPower}, cadenza ${prefs.sensorCadence}, velocità ${prefs.sensorSpeed}; FIT batteria ${prefs.fitBattery}")
        }
        ldi = LdiLink(applicationContext, logger, prefs, ldiListener)
        bestie = BestieLink(applicationContext, logger, prefs, ldiListener)
        if (prefs.enabled) startBikeLink()
        karooSystem = KarooSystemService(applicationContext).also { ks ->
            ks.connect { _ ->
                consumerIds += ks.addConsumer(ActiveRidePage.Params, { _ -> }, {}) { ev: ActiveRidePage ->
                    visibleDataTypeIds.value = ev.page.elements.map { it.dataTypeId }.toSet()
                }
            }
        }
    }

    // ── Bike link ─────────────────────────────────────────────────────────

    private val ldiListener = object : LdiLink.Listener {
        override fun onLiveData(data: LiveData) {
            val before = bikeState.value
            val after = before.merge(data, System.currentTimeMillis())
            bikeState.value = after
            // Every change of the displayed % in the log, with where the bike is: the discharge curve is readable from here too
            if (data.socPct != null && data.socPct != before.socPct)
                logger.log("BIKE ", "Batteria ${before.socPct?.let { "$it → " } ?: ""}${data.socPct} %" +
                    (after.odometerM?.let { ", odometro %.1f km".format(it / 1000.0) } ?: "") +
                    (if (after.chargerConnected == true) ", in carica" else ""))
            if (data.chargerConnected != null && data.chargerConnected != before.chargerConnected)
                logger.log("BIKE ", if (data.chargerConnected) "Caricabatterie collegato" else "Caricabatterie scollegato")
            if (data.lightOn != null && data.lightOn != before.lightOn) logger.log("BIKE ", "Luce ${if (data.lightOn) "accesa" else "spenta"}")
        }
        override fun onLinkChanged(connected: Boolean) {
            bikeState.value = bikeState.value.copy(connected = connected)
            logger.log("BIKE ", if (connected) "Bici collegata: dati in arrivo" else "Bici scollegata: campi su --, sensore in ricerca")
        }
    }

    fun bluetoothGranted(): Boolean = Build.VERSION.SDK_INT < 31 ||
        BLUETOOTH_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startBikeLink() {
        if (!bluetoothGranted()) { logger.log("LDI  ", "Permessi Bluetooth assenti: collegamento alla bici non avviato (concederli nell'app)"); return }
        if (prefs.viaBoard) runCatching { bestie?.start() }.onFailure { logger.logError("BEST ", "Avvio del client verso la scheda fallito", it) }
        else runCatching { ldi?.start() }.onFailure { logger.logError("LDI  ", "Avvio del collegamento alla bici fallito", it) }
    }

    private fun stopBikeLink() {
        bestie?.stop(); ldi?.stop()
        bikeState.value = bikeState.value.copy(connected = false)
    }

    /** The ON/OFF switch of the app and the Bluetooth permissions: the link follows them. */
    fun onEnabledChanged(on: Boolean) {
        if (on) startBikeLink() else stopBikeLink()
    }

    /** The source switch in the app: stop one link, start the other. */
    fun onSourceChanged(viaBoard: Boolean) {
        prefs.viaBoard = viaBoard
        logger.log("SYS  ", "Fonte dei dati: ${if (viaBoard) "scheda BEStie" else "bici diretta"}")
        stopBikeLink()
        if (prefs.enabled) startBikeLink()
    }

    /** The PAIR BIKE button (direct link): start the pairing from scratch. */
    fun pairBike() {
        bikeState.value = BikeState()
        ldi?.forgetBike()
    }

    /** The FORGET BOARD button (board link): look for any BEStie again. */
    fun forgetBoard() {
        bikeState.value = BikeState()
        bestie?.forgetBoard()
    }

    /** For the app screen. */
    fun bikeSummary(): String {
        val s = bikeState.value
        val link = if (prefs.viaBoard) bestie?.status() else ldi?.status()
        return when {
            !prefs.enabled -> "off"
            !bluetoothGranted() -> "Bluetooth permission MISSING: grant it below"
            link == null -> "extension not running"
            s.connected -> link + (s.socPct?.let { ", battery $it %" } ?: "") + (s.odometerM?.let { ", odometer %.1f km".format(it / 1000.0) } ?: "")
            else -> link + (if (s.updatedMs > 0L) ", last data ${(System.currentTimeMillis() - s.updatedMs) / 60_000} min ago" else "")
        }
    }

    // ── The bike as a Karoo sensor ────────────────────────────────────────

    private fun sensorTypes(): List<String> = listOfNotNull(
        if (prefs.sensorPower) DataType.Source.POWER else null,
        if (prefs.sensorCadence) DataType.Source.CADENCE else null,
        if (prefs.sensorSpeed) DataType.Source.SPEED else null)

    override fun startScan(emitter: Emitter<Device>) {
        val types = sensorTypes()
        logger.log("SENS ", "Ricerca sensori del Karoo: offro 'Bosch eBike' con $types")
        if (types.isNotEmpty()) emitter.onNext(Device(extension, DEVICE_UID, types, "Bosch eBike"))
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        if (uid != DEVICE_UID) return
        logger.log("SENS ", "Sensore 'Bosch eBike' collegato dal Karoo")
        var points = 0L
        val job = scope.launch {
            emitter.onNext(OnManufacturerInfo(ManufacturerInfo(manufacturer = "Bosch", modelNumber = "Smart System")))
            var lastConnected: Boolean? = null
            var lastSoc = -1
            while (true) {
                val s = bikeState.value
                if (s.connected != lastConnected) {
                    lastConnected = s.connected
                    logger.log("SENS ", "Sensore: ${if (s.connected) "CONNECTED" else "SEARCHING"} ($points punti finora)")
                    emitter.onNext(OnConnectionStatus(if (s.connected) ConnectionStatus.CONNECTED else ConnectionStatus.SEARCHING))
                }
                if (s.connected) points++
                s.socPct?.let { if (it != lastSoc) { lastSoc = it; emitter.onNext(OnBatteryStatus(BatteryStatus.fromPercentage(it))) } }
                if (s.connected) {
                    if (prefs.sensorPower) s.riderPowerW?.let {
                        emitter.onNext(OnDataPoint(DataPoint(DataType.Source.POWER, mapOf(DataType.Field.POWER to it.toDouble())))) }
                    if (prefs.sensorCadence) s.cadenceRpm?.let {
                        emitter.onNext(OnDataPoint(DataPoint(DataType.Source.CADENCE, mapOf(DataType.Field.CADENCE to it.toDouble())))) }
                    if (prefs.sensorSpeed) s.speedKmh?.let {
                        emitter.onNext(OnDataPoint(DataPoint(DataType.Source.SPEED, mapOf(DataType.Field.SPEED to it / 3.6)))) }
                }
                delay(SENSOR_PERIOD_MS)
            }
        }
        emitter.setCancellable { job.cancel(); logger.log("SENS ", "Sensore 'Bosch eBike' scollegato dal Karoo dopo $points punti") }
    }

    // ── The battery in the FIT file ───────────────────────────────────────

    override fun startFit(emitter: Emitter<FitEffect>) {
        logger.log("FIT  ", "Scrittura FIT avviata: campo bosch_battery ogni ${FIT_PERIOD_MS / 1000} s con la bici collegata")
        var records = 0L
        val job = scope.launch {
            while (true) {
                val s = bikeState.value
                if (prefs.fitBattery && s.connected) s.socPct?.let {
                    emitter.onNext(WriteToRecordMesg(listOf(FieldValue(0, it.toDouble(), FIT_BATTERY))))
                    records++
                    if (records == 1L) logger.log("FIT  ", "Primo valore scritto nel FIT: $it %")
                }
                delay(FIT_PERIOD_MS)
            }
        }
        emitter.setCancellable { job.cancel(); logger.log("FIT  ", "Scrittura FIT fermata dopo $records valori") }
    }

    override fun onDestroy() {
        consumerIds.forEach { id -> karooSystem?.removeConsumer(id) }
        karooSystem?.disconnect()
        bestie?.stop()
        ldi?.stop()
        scope.cancel()
        instance = null
        logger.log("SYS  ", "Estensione fermata")
        super.onDestroy()
    }
}
