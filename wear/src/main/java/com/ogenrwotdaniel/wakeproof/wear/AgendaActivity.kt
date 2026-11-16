package com.ogenrwotdaniel.wakeproof.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * "Today" on its own (2026-11-04; Compose since 2026-11-15): the way in from a
 * row on [AgendaTileService] and from [AgendaComplicationService]. The list and
 * what a tap does live in [AgendaController], shared with [MainActivity]'s Today
 * page, so the two cannot drift apart.
 *
 * Started with [EXTRA_EVENT_KEY] it goes straight to that event's confirm, or
 * its refusal. An outside start can only open a confirm; a session still needs
 * the Start tap. Once a session starts it hands over to [MainActivity], which
 * shows the running block.
 */
class AgendaActivity : ComponentActivity() {

    private val scope = MainScope()
    private lateinit var agenda: AgendaController

    private val dataListener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
        val relevant = events.any { it.dataItem.uri.path == AgendaContract.PATH }
        events.release()
        if (relevant) agenda.onAgendaPushed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agenda = AgendaController(this, scope) {
            // Back to the main screen, which now shows the running block.
            // Started from the tile there is no MainActivity beneath, so
            // bring one up; CLEAR_TOP reuses it when there is.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        agenda.pendingEventKey = eventKeyOf(intent)
        setContent {
            EulerTheme {
                AppScaffold(timeText = { TimeText() }) { AgendaPage(agenda) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        eventKeyOf(intent)?.let { agenda.pendingEventKey = it }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(dataListener)
        agenda.onResume()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(dataListener)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The phone's instance key of an event to open straight to its confirm. */
        const val EXTRA_EVENT_KEY = "event_key"
        private const val MAX_EVENT_KEY_LENGTH = 256

        /** The extra, bounded: the activity is exported, so its input is anyone's. */
        fun eventKeyOf(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_EVENT_KEY)?.take(MAX_EVENT_KEY_LENGTH)
    }
}
