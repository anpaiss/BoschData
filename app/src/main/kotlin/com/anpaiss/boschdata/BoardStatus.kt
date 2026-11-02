package com.anpaiss.boschdata

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The STATUS characteristic of the BoschData Service on the BEStie board
 * (layout 1, 55 bytes, little endian): what the board knows about itself and
 * about its link with the bike. Pushed every 5 s and on every link change.
 */
data class BoardStatus(
    val layout: Int,
    val fw: String,
    val variant: Int,
    val uptimeS: Long,
    val bikeConnected: Boolean,
    /** The board's own battery, null before its first measurement. */
    val boardBattPct: Int?,
    val links: Int,
    val bondedPeers: Int,
    val ldiMessages: Long,
    /** Seconds since the last message from the bike, null if none since boot. */
    val ldiAgeS: Long?,
    val txDropped: Int,
    val boots: Int,
    val resetReason: Long,
    val faultId: Long,
    val faultCode: Long,
    val faultPc: Long,
    val faultLine: Int,
    val faultFile: String,
) {
    val hasFault get() = faultId != 0L

    fun resetReasonText(): String {
        if (resetReason == 0L) return "power on"
        val bits = listOf(0 to "reset pin", 1 to "watchdog", 2 to "soft reset", 3 to "lockup", 16 to "system off",
            17 to "lpcomp", 18 to "debug", 19 to "nfc", 20 to "vbus")
        return bits.filter { resetReason and (1L shl it.first) != 0L }.joinToString("+") { it.second }.ifEmpty { "0x%X".format(resetReason) }
    }

    fun faultText(): String = when (faultId) {
        0L -> "none"
        0x8001L -> "HARD FAULT pc 0x%X lr 0x%X".format(faultPc, faultCode)
        0x4001L -> "SDK ERROR 0x%X at $faultFile:$faultLine".format(faultCode)
        0x4002L -> "ASSERT at $faultFile:$faultLine"
        1L -> "SOFTDEVICE ASSERT 0x%X".format(faultCode)
        2L -> "SOFTDEVICE MEMACC 0x%X".format(faultCode)
        else -> "fault 0x%X code 0x%X $faultFile:$faultLine".format(faultId, faultCode)
    }

    /** One line for the log and the app screen. */
    fun summary(): String = "fw $fw-k$variant, up ${uptimeS / 60} min, boot #$boots (${resetReasonText()}), " +
        "bike ${if (bikeConnected) "UP" else "down"}, ${ldiMessages} LDI msgs" +
        (ldiAgeS?.let { ", last $it s ago" } ?: ", none yet") +
        ", links $links, bonds $bondedPeers, batt ${boardBattPct?.let { "$it %" } ?: "?"}, dropped $txDropped" +
        (if (hasFault) ", LAST FAULT: ${faultText()}" else "")

    /** The fields whose change is worth a log line on their own. */
    fun linkKey() = listOf(bikeConnected, links, bondedPeers, boots, faultId, faultCode, txDropped)

    companion object {
        const val SIZE = 55

        /** Throws on a short or unknown layout. */
        fun decode(b: ByteArray): BoardStatus {
            require(b.size >= SIZE) { "status of ${b.size} bytes, expected $SIZE" }
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val layout = buf.get().toInt() and 0xff
            require(layout == 1) { "status layout $layout" }
            val fw = "${buf.get().toInt() and 0xff}.${buf.get().toInt() and 0xff}.${buf.get().toInt() and 0xff}"
            val variant = buf.get().toInt() and 0xff
            val uptime = buf.int.toLong() and 0xffffffffL
            val bike = buf.get().toInt() != 0
            val batt = (buf.get().toInt() and 0xff).takeIf { it <= 100 }
            val links = buf.get().toInt() and 0xff
            val bonded = buf.get().toInt() and 0xff
            val msgs = buf.int.toLong() and 0xffffffffL
            val age = (buf.int.toLong() and 0xffffffffL).takeIf { it != 0xffffffffL }
            val dropped = buf.short.toInt() and 0xffff
            val boots = buf.short.toInt() and 0xffff
            val reason = buf.int.toLong() and 0xffffffffL
            val faultId = buf.int.toLong() and 0xffffffffL
            val faultCode = buf.int.toLong() and 0xffffffffL
            val faultPc = buf.int.toLong() and 0xffffffffL
            val faultLine = buf.short.toInt() and 0xffff
            val file = ByteArray(12).also { buf.get(it) }
            val fileStr = String(file, Charsets.US_ASCII).substringBefore('\u0000')
            return BoardStatus(layout, fw, variant, uptime, bike, batt, links, bonded, msgs, age, dropped, boots, reason,
                faultId, faultCode, faultPc, faultLine, fileStr)
        }
    }
}
