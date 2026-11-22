package com.anpaiss.boschdata

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The popup a tap on BIKE LINK opens, over the ride screen: what is wrong and,
 * where something can be done from the Karoo, one button to do it.
 * Built in code, closes by itself after a minute so it never stays on a ride page.
 */
class LinkHelpActivity : AppCompatActivity() {

    companion object {
        private const val AUTO_CLOSE_MS = 60_000L

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, LinkHelpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private val autoClose = Runnable { finish() }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setFinishOnTouchOutside(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(root)
        build()
    }

    override fun onResume() { super.onResume(); root.postDelayed(autoClose, AUTO_CLOSE_MS) }
    override fun onPause() { super.onPause(); root.removeCallbacks(autoClose) }

    private fun build() {
        root.removeAllViews()
        val ext = BoschDataExtension.instance
            ?: return show("BOSCHDATA NOT RUNNING", "The extension is not running. Open BoschData from the apps to start it.")
        val s = ext.currentState()
        val link = s.link()
        BoschDataLogger.shared?.log("SYS  ", "Popup BIKE LINK aperto: $link")
        when (link) {
            Link.LINKED -> show("BIKE LINKED", ext.bikeSummary())
            Link.BIKE_OFF -> show("BIKE OFF",
                if (s.viaBoard) "The board is linked but the bike sends nothing. Switch the bike on: data arrive in a few seconds.\n\n" +
                    "Still nothing after a minute: in Bosch Flow, add BEStie again."
                else "The bike sends nothing. Switch the bike on. With the direct link the bike loses the Karoo at every " +
                    "Bluetooth restart: PAIR BIKE again in the BoschData app.")
            Link.LINK_OFF -> show("BOSCHDATA OFF", "BoschData is switched off in its app: no Bluetooth, no data.",
                "TURN BOSCHDATA ON") {
                Prefs(this).enabled = true
                ext.onEnabledChanged(true)
            }
            Link.NO_BOARD -> show("BOARD NOT FOUND",
                "No answer from the BEStie board. Is it switched on and charged?" +
                    (s.boardLastPct?.let { " Last seen at $it %." } ?: ""),
                "SEARCH AGAIN") { ext.searchAgain() }
            Link.BT_OFF -> show("BLUETOOTH OFF",
                (if (s.recording) "The Karoo turned Bluetooth off at START" else "Bluetooth is off") +
                    ": BoschData can't reach the bike.\n\nFor next rides: pair a Bluetooth LE sensor in Sensors " +
                    "and keep it in this profile.",
                "TURN BLUETOOTH ON") { ext.turnBluetoothOn() }
            Link.NO_PERM -> show("NO PERMISSION", "BoschData needs the Bluetooth permission.", "GRANT BLUETOOTH") {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun show(title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        root.addView(TextView(this).apply {
            this.text = title; textSize = 22f; setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.03f
        })
        root.addView(TextView(this).apply { this.text = text; textSize = 16f; setPadding(0, 12, 0, 16) })
        fun button(label: String, main: Boolean, onClick: () -> Unit) = root.addView(Button(this).apply {
            this.text = label
            if (main) { setBackgroundColor(getColor(R.color.field_ok)); setTextColor(Color.WHITE) }
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        if (action != null && onAction != null) button(action, main = true) { onAction(); finish() }
        button("CLOSE", main = false) { finish() }
    }
}
