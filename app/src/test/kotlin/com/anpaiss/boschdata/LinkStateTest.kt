package com.anpaiss.boschdata

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStateTest {

    private val riding = BikeState(connected = true, socPct = 73, boardBattPct = 82, boardLinked = true)

    private fun face(kind: BikeFieldDataType.Kind, s: BikeState) =
        BikeFieldDataType("t", kind.name, kind, MutableStateFlow(s), MutableStateFlow(null)).render(s)

    @Test fun linkStates() {
        assertEquals(Link.LINKED, riding.link())
        assertEquals(Link.BIKE_OFF, riding.copy(connected = false).link())
        assertEquals(Link.NO_BOARD, riding.copy(connected = false, boardLinked = false).link())
        assertEquals(Link.BT_OFF, riding.copy(btOn = false, boardLinked = false).link())
        assertEquals(Link.NO_PERM, riding.copy(permission = false, btOn = false).link())
        assertEquals(Link.LINK_OFF, riding.copy(enabled = false, permission = false).link())
        // Direct link: no board to wait for
        assertEquals(Link.LINKED, riding.copy(viaBoard = false, boardLinked = false).link())
        assertEquals(Link.BIKE_OFF, riding.copy(viaBoard = false, boardLinked = false, connected = false).link())
    }

    @Test fun linkField() {
        val k = BikeFieldDataType.Kind.LINK
        assertEquals(BikeFieldDataType.Face("BIKE LINK", "LINKED", "board 82 %", Level.OK), face(k, riding))
        assertEquals(BikeFieldDataType.Face("BIKE LINK", "BIKE OFF", "board ok", Level.WARN), face(k, riding.copy(connected = false)))
        assertEquals(BikeFieldDataType.Face("BIKE LINK", "BT OFF", "by Karoo", Level.BAD),
            face(k, riding.copy(btOn = false, recording = true)))
    }

    @Test fun bikeOffIsOrangeErrorsAreRed() {
        val k = BikeFieldDataType.Kind.BATTERY
        assertEquals(Level.WARN, face(k, riding.copy(connected = false)).level)
        assertEquals(Level.BAD, face(k, riding.copy(connected = false, boardLinked = false)).level)
        assertEquals(Level.BAD, face(k, riding.copy(connected = false, btOn = false)).level)
        val b = BikeFieldDataType.Kind.BOARD_BATTERY
        assertEquals(Level.WARN, face(b, riding.copy(boardBattPct = 12)).level)
        assertEquals(Level.BAD, face(b, riding.copy(boardBattPct = null, boardLinked = false)).level)
    }
}
