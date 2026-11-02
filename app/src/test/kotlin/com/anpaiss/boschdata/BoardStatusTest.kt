package com.anpaiss.boschdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BoardStatusTest {

    /** Builds the 51 bytes the firmware packs (bds_status_t, layout 1). */
    private fun packed(bike: Boolean, batt: Int, age: Long, faultId: Long, faultCode: Long, line: Int, file: String): ByteArray {
        val b = ByteBuffer.allocate(BoardStatus.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(1); b.put(0); b.put(2); b.put(2); b.put(1)             // layout, fw 0.2.2, variant 1
        b.putInt(3661)                                             // uptime 1 h 1 min 1 s
        b.put(if (bike) 1 else 0); b.put(batt.toByte()); b.put(2); b.put(1)
        b.putInt(1234); b.putInt(age.toInt())
        b.putShort(3); b.putShort(7)                               // dropped, boots
        b.putInt(0x4)                                              // reset reason: soft reset
        b.putInt(faultId.toInt()); b.putInt(faultCode.toInt()); b.putInt(0x27abc); b.putShort(line.toShort())
        val f = ByteArray(12); file.toByteArray().copyInto(f, 0, 0, minOf(12, file.length)); b.put(f)
        return b.array()
    }

    @Test fun `a healthy status decodes field by field`() {
        val s = BoardStatus.decode(packed(bike = true, batt = 87, age = 2, faultId = 0, faultCode = 0, line = 0, file = ""))
        assertEquals("0.2.2", s.fw); assertEquals(1, s.variant)
        assertEquals(3661L, s.uptimeS)
        assertTrue(s.bikeConnected); assertEquals(87, s.boardBattPct)
        assertEquals(2, s.links); assertEquals(1, s.bondedPeers)
        assertEquals(1234L, s.ldiMessages); assertEquals(2L, s.ldiAgeS)
        assertEquals(3, s.txDropped); assertEquals(7, s.boots)
        assertEquals("soft reset", s.resetReasonText())
        assertFalse(s.hasFault); assertEquals("none", s.faultText())
        assertTrue(s.summary(), s.summary().contains("bike UP") && s.summary().contains("boot #7"))
    }

    @Test fun `unknown battery and no LDI message yet are null, a fault is spelled out`() {
        val s = BoardStatus.decode(packed(bike = false, batt = 255, age = 0xffffffffL, faultId = 0x4001, faultCode = 0x4, line = 123, file = "services.c"))
        assertNull(s.boardBattPct); assertNull(s.ldiAgeS)
        assertTrue(s.hasFault)
        assertEquals("SDK ERROR 0x4 at services.c:123", s.faultText())
        assertTrue(s.summary().contains("LAST FAULT"))
    }

    @Test fun `a hard fault shows pc and lr, a short message throws`() {
        val s = BoardStatus.decode(packed(bike = false, batt = 50, age = 10, faultId = 0x8001, faultCode = 0x1234, line = 0, file = "hardfault"))
        assertEquals("HARD FAULT pc 0x27ABC lr 0x1234", s.faultText())
        assertThrows(IllegalArgumentException::class.java) { BoardStatus.decode(ByteArray(10)) }
    }

    @Test fun `the full LiveData the board re-encodes decodes like the bike's own first message`() {
        // every field present, as the board sends it (nanopb encodes the fields set in the state): same bytes as the bike's READ
        val d = LiveData.decode(byteArrayOf(0x08, 0x00, 0x10, 0x00, 0x28, 0x00, 0x48, 0xbd.toByte(), 0xb9.toByte(), 0x01, 0x50, 0x2c,
            0x58, 0x80.toByte(), 0xa5.toByte(), 0x93.toByte(), 0xd7.toByte(), 0x06, 0x60, 0xaa.toByte(), 0xa6.toByte(), 0x3e,
            0x88.toByte(), 0x01, 0x02, 0xa8.toByte(), 0x01, 0x00, 0xb0.toByte(), 0x01, 0x00, 0xb8.toByte(), 0x01, 0x00,
            0xc0.toByte(), 0x01, 0x00, 0xc8.toByte(), 0x01, 0x01))
        assertEquals(44, d.socPct); assertEquals(1020714L, d.odometerM); assertEquals(true, d.lightOn)
    }
}
