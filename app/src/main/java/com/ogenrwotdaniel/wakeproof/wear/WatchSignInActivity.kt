package com.ogenrwotdaniel.wakeproof.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
import com.ogenrwotdaniel.wakeproof.data.sync.SignInCancelled
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Translucent: the watch's "Sign in to cloud" lands here
 * (`WearContract.CMD_SIGN_IN`). Shows the account picker on the phone, sends
 * the token to the watch ([WatchSignIn]) and finishes: the same thing as
 * Settings → Sync → Sign in the watch, without going there. The watch's own
 * picker (Samsung's) crashed on every tap, seen 2026-11-20.
 */
@AndroidEntryPoint
class WatchSignInActivity : ComponentActivity() {

    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var watchSignIn: WatchSignIn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation (rotation) would pop a second picker over the first.
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            val text = accounts.idTokenForCompanion(this@WatchSignInActivity).fold(
                onSuccess = { token ->
                    if (watchSignIn.send(token) > 0) "Sent to the watch." else "The watch is out of reach."
                },
                onFailure = { error ->
                    if (error is SignInCancelled) error.message else "Couldn't sign in the watch: ${error.message}"
                }
            )
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, WatchSignInActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
