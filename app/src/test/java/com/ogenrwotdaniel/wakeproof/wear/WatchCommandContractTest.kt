package com.ogenrwotdaniel.wakeproof.wear

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/wakeproof/command` is copied by hand into the watch's `TimerCommands`; the copies must agree. */
class WatchCommandContractTest {

    @Test
    fun `the watch sends every command the phone listens for, spelled the same`() {
        val watch = File("../wear/src/main/java/com/ogenrwotdaniel/wakeproof/wear/TimerCommands.kt")
        assertTrue("run from the app module", watch.exists())
        val source = watch.readText()
        for (command in listOf(
            WearContract.COMMAND_PATH,
            WearContract.CMD_TIMER_START,
            WearContract.CMD_TIMER_RESUME,
            WearContract.CMD_SIGN_IN,
            WearContract.CMD_AGENDA_DONE_PREFIX
        )) {
            assertTrue("missing \"$command\" on the watch", "\"$command\"" in source)
        }
    }
}
