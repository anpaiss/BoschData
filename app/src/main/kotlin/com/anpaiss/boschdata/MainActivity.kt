package com.anpaiss.boschdata

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Settings, permission, link state and the pairing instructions. Built in code, no layout XML. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvBike: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply {
            text = "BoschData ${BuildConfig.VERSION_NAME}"
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#FF5823"))
        })
        root.addView(label("Your Bosch Smart System e-bike on the Karoo, over Bluetooth (Bosch Live Data Interface). " +
            "Fields: bike battery, rider power, speed, cadence, odometer, light. The bike can also be a Karoo sensor " +
            "and the battery goes in the FIT file of every ride."))

        root.addView(CheckBox(this).apply {
            text = "BoschData ON (off = no Bluetooth, no data)"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; BoschDataExtension.instance?.onEnabledChanged(on); refresh() }
        })
        tvBike = label("")
        root.addView(tvBike)
        root.addView(Button(this).apply {
            text = "GRANT BLUETOOTH"
            setOnClickListener { requestPermissions(BoschDataExtension.BLUETOOTH_PERMISSIONS, 1) }
        })

        root.addView(label("Pairing, once:\n1. Switch the bike on.\n2. Bosch Flow app → Add component → Accessories → " +
            "\"Karoo BT Audio\" (that is the Karoo).\nFrom then on the bike connects by itself whenever it is on."))

        root.addView(label("The bike as a Karoo sensor (Karoo: Sensors → add → BoschData → Bosch eBike):").apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(CheckBox(this).apply {
            text = "Rider power (the Bosch torque sensor; recorded like a power meter)"
            isChecked = prefs.sensorPower
            setOnCheckedChangeListener { _, on -> prefs.sensorPower = on }
        })
        root.addView(CheckBox(this).apply {
            text = "Cadence (off if you already have a cadence sensor)"
            isChecked = prefs.sensorCadence
            setOnCheckedChangeListener { _, on -> prefs.sensorCadence = on }
        })
        root.addView(CheckBox(this).apply {
            text = "Speed from the wheel (off = the Karoo GPS speed)"
            isChecked = prefs.sensorSpeed
            setOnCheckedChangeListener { _, on -> prefs.sensorSpeed = on }
        })
        root.addView(label("Changing these after pairing: remove and add the sensor again on the Karoo."))

        root.addView(CheckBox(this).apply {
            text = "Write the battery % in the FIT file of every ride (field bosch_battery)"
            isChecked = prefs.fitBattery
            setOnCheckedChangeListener { _, on -> prefs.fitBattery = on }
        })

        root.addView(Button(this).apply { text = "REFRESH"; setOnClickListener { refresh() } })
        root.addView(label("Log: /sdcard/Android/data/com.anpaiss.boschdata/files/bosch_data.log"))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        BoschDataExtension.instance?.onEnabledChanged(prefs.enabled)
        refresh()
    }

    private fun refresh() {
        tvBike.text = "Bike: " + (BoschDataExtension.instance?.bikeSummary() ?: "extension not running")
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, 8, 0, 8) }
}
