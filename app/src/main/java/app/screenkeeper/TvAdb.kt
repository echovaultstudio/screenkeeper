package app.screenkeeper

import android.content.Context

/** Small jobs the phone runs on the TV over adb (the TV's Network debugging). */
object TvAdb {
    /** Switch the phone keyboard on (re-binding it if it was stuck) and let the TV keep it on by itself. */
    fun enableKeyboard(adb: AdbClient, pkg: String) {
        val current = adb.shell("settings get secure enabled_accessibility_services")
        for (c in KeyboardService.enableShellCommands(pkg, current)) adb.shell(c)
    }

    /** Address for adb: the last one used, or the paired TV on port 5555. */
    fun address(ctx: Context): Pair<String, Int>? {
        val store = RuleStore(ctx)
        val a = store.adbAddress() ?: store.savedRemote()?.host ?: return null
        return AdbClient.parseAddress(a)
    }
}
