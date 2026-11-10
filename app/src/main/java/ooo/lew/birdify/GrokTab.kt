package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge

/**
 * Removes the Grok tab from the bottom navigation bar.
 *
 * X builds the bar from every tab it knows and drops Grok only when the
 * android_webview_grok_tab_enabled feature switch is off, which it defaults
 * to on. Answering that switch with false removes the tab the way X itself
 * would. The feature switch store keeps its real names through R8, so nothing
 * here depends on an obfuscated symbol.
 */
object GrokTab {

    private const val LOG = "birdify"
    private const val SWITCHES = "com.x.featureswitches.FeatureSwitchesRepositoryImpl"
    private const val GROK_TAB_SWITCH = "android_webview_grok_tab_enabled"

    @Volatile
    private var hidden = false

    fun hook(cl: ClassLoader) {
        try {
            val switches = Class.forName(SWITCHES, false, cl)
            val targets = XposedBridge.hookAllMethods(switches, "getBoolean", switchHook)
            XposedBridge.log(
                if (targets.isNotEmpty()) "$LOG: grok tab hook installed on $SWITCHES"
                else "$LOG: grok tab found no getBoolean on $SWITCHES"
            )
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: grok tab unavailable: $t")
        }
    }

    private val switchHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.firstOrNull() != GROK_TAB_SWITCH) return
            param.result = false
            if (!hidden) {
                hidden = true
                XposedBridge.log("$LOG: grok tab hidden")
            }
        }
    }
}
