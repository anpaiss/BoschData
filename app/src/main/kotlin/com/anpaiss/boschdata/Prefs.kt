package com.anpaiss.boschdata

import android.content.Context
import android.content.SharedPreferences

/** User settings. */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Master switch: off = no Bluetooth, no fields, nothing. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Where the bike data comes from: the BEStie board (the Karoo is a central) or the bike directly (the Karoo is the LDI accessory). */
    var viaBoard: Boolean
        get() = sp.getBoolean(KEY_VIA_BOARD, true)
        set(v) = sp.edit().putBoolean(KEY_VIA_BOARD, v).apply()

    /** Bluetooth address of the BEStie board that exposed the BoschData Service; null until the first connection. */
    var boardAddress: String?
        get() = sp.getString(KEY_BOARD_ADDRESS, null)
        set(v) = sp.edit().putString(KEY_BOARD_ADDRESS, v).apply()

    /** Bluetooth address of the bike that exposed the Live Data Service; null until the first connection. */
    var bikeAddress: String?
        get() = sp.getString(KEY_BIKE_ADDRESS, null)
        set(v) = sp.edit().putString(KEY_BIKE_ADDRESS, v).apply()

    /** What the bike offers as a Karoo sensor. Power on by default: nobody has a power meter on an e-bike. */
    var sensorPower: Boolean
        get() = sp.getBoolean(KEY_SENSOR_POWER, true)
        set(v) = sp.edit().putBoolean(KEY_SENSOR_POWER, v).apply()

    /** Off by default: most riders already have a cadence sensor, and the Karoo would see two. */
    var sensorCadence: Boolean
        get() = sp.getBoolean(KEY_SENSOR_CADENCE, false)
        set(v) = sp.edit().putBoolean(KEY_SENSOR_CADENCE, v).apply()

    /** Off by default: the Karoo's GPS speed is what everybody is used to. */
    var sensorSpeed: Boolean
        get() = sp.getBoolean(KEY_SENSOR_SPEED, false)
        set(v) = sp.edit().putBoolean(KEY_SENSOR_SPEED, v).apply()

    /** The battery % written in the FIT file of every ride as a developer field. */
    var fitBattery: Boolean
        get() = sp.getBoolean(KEY_FIT_BATTERY, true)
        set(v) = sp.edit().putBoolean(KEY_FIT_BATTERY, v).apply()

    companion object {
        private const val NAME = "bosch_data_prefs"
        private const val KEY_ENABLED        = "enabled"
        private const val KEY_BIKE_ADDRESS   = "bike_address"
        private const val KEY_VIA_BOARD      = "via_board"
        private const val KEY_BOARD_ADDRESS  = "board_address"
        private const val KEY_SENSOR_POWER   = "sensor_power"
        private const val KEY_SENSOR_CADENCE = "sensor_cadence"
        private const val KEY_SENSOR_SPEED   = "sensor_speed"
        private const val KEY_FIT_BATTERY    = "fit_battery"
    }
}
