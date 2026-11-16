package com.ogenrwotdaniel.wakeproof.wear.walls

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * The watch's wall screen: which wall is up and when gated apps next open, with one
 * button to the watch face. Plain Views: the wall must land at once, and it needs
 * none of the Compose screens' state.
 *
 * Back and the button both go home rather than finishing: finishing would uncover the
 * walled app underneath, and [WatchWallService] would only throw the wall up again.
 */
class WatchBlockedActivity : ComponentActivity() {

    private lateinit var title: TextView
    private lateinit var detail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.widthPixels * 0.14f).toInt()
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        detail = TextView(this).apply {
            setTextColor(Color.parseColor("#B8C2C8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, pad / 4, 0, pad / 4)
        }
        val home = Button(this).apply {
            text = HOME_LABEL
            setOnClickListener { goHome() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(detail)
            addView(home)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0F1417"))
                isFillViewport = true
                addView(column)
            }
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            }
        )
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        // Bounded: an extra is caller-supplied text, and this screen shows nothing long.
        title.text = intent.getStringExtra(EXTRA_TITLE)?.take(80) ?: WatchWall.SCHEDULE.title
        detail.text = intent.getStringExtra(EXTRA_DETAIL)?.take(400).orEmpty()
    }

    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "com.ogenrwotdaniel.wakeproof.wear.walls.TITLE"
        const val EXTRA_DETAIL = "com.ogenrwotdaniel.wakeproof.wear.walls.DETAIL"
        private const val HOME_LABEL = "Watch face"
    }
}
