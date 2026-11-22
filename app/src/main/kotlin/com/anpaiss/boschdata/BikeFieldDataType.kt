package com.anpaiss.boschdata

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The last values received from the bike, merged notification after notification (each carries only what changed). */
data class BikeState(
    val connected: Boolean = false,
    /** When the last message arrived, 0 = never. */
    val updatedMs: Long = 0L,
    val socPct: Int? = null,
    val riderPowerW: Int? = null,
    val speedKmh: Double? = null,
    val cadenceRpm: Int? = null,
    val odometerM: Long? = null,
    val lightOn: Boolean? = null,
    val lux: Double? = null,
    val chargerConnected: Boolean? = null,
    val locked: Boolean? = null,
    /** The BEStie board's battery, null while the board is not linked; the last one read stays below. */
    val boardBattPct: Int? = null,
    val boardLastPct: Int? = null,
    /** The link around the bike: app switch, permission, Android Bluetooth, source, board link, ride recording. */
    val enabled: Boolean = true,
    val permission: Boolean = true,
    val btOn: Boolean = true,
    val viaBoard: Boolean = true,
    val boardLinked: Boolean = false,
    val recording: Boolean = false,
) {
    /** The same link conditions with the bike's data cleared (PAIR BIKE, FORGET BOARD). */
    fun cleared() = BikeState(enabled = enabled, permission = permission, btOn = btOn, viaBoard = viaBoard, recording = recording)

    fun merge(d: LiveData, nowMs: Long) = copy(updatedMs = nowMs,
        socPct = d.socPct ?: socPct, riderPowerW = d.riderPowerW ?: riderPowerW, speedKmh = d.speedKmh ?: speedKmh,
        cadenceRpm = d.cadenceRpm ?: cadenceRpm, odometerM = d.odometerM ?: odometerM, lightOn = d.lightOn ?: lightOn,
        lux = d.lux ?: lux, chargerConnected = d.chargerConnected ?: chargerConnected, locked = d.locked ?: locked)
}

/** Green: all good. Orange: a warning, nothing broken (bike switched off). Red: an error to fix. */
enum class Level { OK, WARN, BAD }

/** What the BIKE LINK field says; the first condition that fails wins. */
enum class Link(val word: String, val level: Level) {
    LINKED("LINKED", Level.OK),
    BIKE_OFF("BIKE OFF", Level.WARN),
    LINK_OFF("LINK OFF", Level.WARN),
    NO_BOARD("NO BOARD", Level.BAD),
    BT_OFF("BT OFF", Level.BAD),
    NO_PERM("NO PERM", Level.BAD),
}

fun BikeState.link(): Link = when {
    !enabled -> Link.LINK_OFF
    !permission -> Link.NO_PERM
    !btOn -> Link.BT_OFF
    viaBoard && !boardLinked -> Link.NO_BOARD
    !connected -> Link.BIKE_OFF
    else -> Link.LINKED
}

/**
 * Single-cell fields fed straight by the bike over the Live Data Interface:
 * battery, rider power (the Bosch torque sensor), wheel speed, cadence,
 * odometer, light. "--" while the bike is off, with the last value below it.
 * Green background while the bike feeds the field, orange when the bike is
 * just off (a warning), red when something is broken (board, Bluetooth,
 * permission). BIKE LINK says which, and a tap on it opens [LinkHelpActivity].
 * The stream carries the number and goes NotAvailable with the bike off: other
 * extensions (boschRange) read the battery from it.
 */
class BikeFieldDataType(
    extension: String,
    typeId: String,
    private val kind: Kind,
    private val state: StateFlow<BikeState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?>,
) : DataTypeImpl(extension, typeId) {

    enum class Kind { BATTERY, POWER, SPEED, CADENCE, ODOMETER, LIGHT, BOARD_BATTERY, LINK }
    internal data class Face(val caption: String, val value: String, val sub: String, val level: Level)

    companion object {
        private const val REDRAW_THROTTLE_MS = 1_000L
        private const val CHAR_EM = 0.62f
        /** Capital letters are wider than digits: BIKE LINK's words and ON/OFF. */
        private const val CHAR_EM_LETTERS = 0.72f
        /** Below this the board's field turns red: time to charge it. */
        private const val BOARD_LOW_PCT = 20
    }

    private fun number(s: BikeState): Double? {
        // The board's battery does not depend on the bike: it is there whenever the board is linked
        if (kind == Kind.BOARD_BATTERY) return s.boardBattPct?.toDouble()
        // The link as a number for other extensions: 0 = LINKED, then the order of [Link]
        if (kind == Kind.LINK) return s.link().ordinal.toDouble()
        if (!s.connected) return null
        return when (kind) {
            Kind.BATTERY  -> s.socPct?.toDouble()
            Kind.POWER    -> s.riderPowerW?.toDouble()
            Kind.SPEED    -> s.speedKmh?.let { Math.round(it * 10.0) / 10.0 }
            Kind.CADENCE  -> s.cadenceRpm?.toDouble()
            Kind.ODOMETER -> s.odometerM?.let { Math.round(it / 100.0) / 10.0 }
            Kind.LIGHT    -> s.lightOn?.let { if (it) 1.0 else 0.0 }
            Kind.BOARD_BATTERY, Kind.LINK -> null
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            state.map { number(it) }.distinctUntilChanged().collect { v ->
                emitter.onNext(if (v == null) StreamState.NotAvailable
                    else StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val density = context.resources.displayMetrics.density
        val widthDp = config.viewSize.first / density - 16f
        val heightDp = config.viewSize.second / density
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            val tap = if (kind == Kind.LINK) LinkHelpActivity.pendingIntent(context) else null
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { face ->
                    val byHeight = heightDp * 0.40f
                    val em = if (face.value.any { it.isLetter() }) CHAR_EM_LETTERS else CHAR_EM
                    val byWidth = widthDp / (em * face.value.length.coerceAtLeast(3))
                    val valueSp = minOf(byHeight, byWidth).coerceIn(16f, 80f)
                    val captionSp = (valueSp * 0.32f).coerceIn(10f, 16f)
                    val subSp = (valueSp * 0.40f).coerceIn(10f, 20f)
                    val small = heightDp < 60f
                    val views = RemoteViews(context.packageName, R.layout.view_field)
                    views.setTextViewText(R.id.tv_caption, face.caption)
                    // A no-break space: a two-word value ("NO BOARD") shrinks, it never wraps to its first word
                    views.setTextViewText(R.id.tv_value, face.value.replace(' ', '\u00A0'))
                    views.setTextViewText(R.id.tv_sub, face.sub)
                    views.setTextViewTextSize(R.id.tv_caption, TypedValue.COMPLEX_UNIT_SP, captionSp)
                    views.setTextViewTextSize(R.id.tv_value, TypedValue.COMPLEX_UNIT_SP, valueSp)
                    views.setTextViewTextSize(R.id.tv_sub, TypedValue.COMPLEX_UNIT_SP, subSp)
                    views.setViewVisibility(R.id.tv_caption, if (small) View.GONE else View.VISIBLE)
                    views.setViewVisibility(R.id.tv_sub, if (small || face.sub.isEmpty()) View.GONE else View.VISIBLE)
                    // Green, orange or red: the state of the link is visible at a glance while riding
                    views.setInt(R.id.field_root, "setBackgroundColor", context.getColor(when (face.level) {
                        Level.OK -> R.color.field_ok; Level.WARN -> R.color.field_warn; Level.BAD -> R.color.field_bad }))
                    tap?.let { views.setOnClickPendingIntent(R.id.field_root, it) }
                    views.setTextColor(R.id.tv_value, context.getColor(R.color.field_text))
                    views.setTextColor(R.id.tv_caption, context.getColor(R.color.field_text_muted))
                    views.setTextColor(R.id.tv_sub, context.getColor(R.color.field_text_muted))
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun value(s: BikeState): String? = when (kind) {
        Kind.BATTERY  -> s.socPct?.let { "$it %" }
        Kind.POWER    -> s.riderPowerW?.let { "$it W" }
        Kind.SPEED    -> s.speedKmh?.let { "%.1f".format(it) }
        Kind.CADENCE  -> s.cadenceRpm?.let { "$it" }
        Kind.ODOMETER -> s.odometerM?.let { "%.1f".format(it / 1000.0) }
        Kind.LIGHT    -> s.lightOn?.let { if (it) "ON" else "OFF" }
        Kind.BOARD_BATTERY -> s.boardBattPct?.let { "$it %" }
        Kind.LINK -> s.link().word
    }

    /** Under "--" in a field without its last value: the reason, the same word BIKE LINK shows. */
    private fun noData(link: Link) = when (link) {
        Link.NO_BOARD -> "no board"; Link.BT_OFF -> "BT off"; Link.NO_PERM -> "no permission"; Link.LINK_OFF -> "BoschData off"
        Link.BIKE_OFF, Link.LINKED -> "bike off"
    }

    internal fun render(s: BikeState): Face {
        val link = s.link()
        // Without data: orange when the bike is only switched off (or BoschData is off), red when something is broken
        val missing = if (link == Link.BIKE_OFF || link == Link.LINK_OFF) Level.WARN else Level.BAD
        if (kind == Kind.LINK) {
            val sub = when (link) {
                Link.LINKED   -> if (s.viaBoard) s.boardBattPct?.let { "board $it %" } ?: "board ok" else "direct"
                Link.BIKE_OFF -> if (s.viaBoard) "board ok" else "waiting bike"
                Link.LINK_OFF -> "BoschData off"
                Link.NO_BOARD -> "searching"
                Link.BT_OFF   -> if (s.recording) "by Karoo" else "Bluetooth"
                Link.NO_PERM  -> "grant Bluetooth"
            }
            return Face("BIKE LINK", link.word, sub, link.level)
        }
        if (kind == Kind.BOARD_BATTERY) {
            val p = s.boardBattPct ?: return Face("BESTIE BATTERY", "--", s.boardLastPct?.let { "last $it %" } ?: "board not found",
                if (link == Link.LINK_OFF) Level.WARN else Level.BAD)
            return Face("BESTIE BATTERY", "$p %", if (p < BOARD_LOW_PCT) "charge it" else "", if (p < BOARD_LOW_PCT) Level.WARN else Level.OK)
        }
        val caption = when (kind) {
            Kind.BATTERY -> "BIKE BATTERY"; Kind.POWER -> "RIDER POWER"; Kind.SPEED -> "BIKE SPEED"
            Kind.CADENCE -> "CADENCE"; Kind.ODOMETER -> "ODOMETER"; Kind.LIGHT -> "BIKE LIGHT"
            Kind.BOARD_BATTERY -> "BESTIE BATTERY"; Kind.LINK -> "BIKE LINK"
        }
        val v = value(s)
        if (!s.connected) return Face(caption, "--", if (v != null && kind != Kind.POWER && kind != Kind.SPEED && kind != Kind.CADENCE) "last $v" else noData(link), missing)
        val sub = when (kind) {
            Kind.BATTERY  -> if (s.chargerConnected == true) "charging" else ""
            Kind.SPEED    -> "km/h"
            Kind.CADENCE  -> "rpm"
            Kind.ODOMETER -> "km"
            Kind.LIGHT    -> s.lux?.let { "%.0f lx".format(it) } ?: ""
            Kind.POWER, Kind.BOARD_BATTERY, Kind.LINK -> ""
        }
        return Face(caption, v ?: "--", sub, Level.OK)
    }
}
