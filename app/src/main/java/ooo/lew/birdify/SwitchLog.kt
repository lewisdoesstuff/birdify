package ooo.lew.birdify

import android.app.AndroidAppHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Records the feature switches X reads, to find ones worth answering.
 *
 * Off unless a file named birdify-switch-log exists in X's cache directory,
 * so it costs nothing in normal use. Each key is written once, and again whenever its value changes, to
 * switches.tsv in X's cache directory with its type, value, default and the
 * first caller outside the switch store. The whole store is dumped once to
 * switches-all.tsv, since it also holds keys this session never reads.
 */
object SwitchLog {

    private const val LOG = "birdify"
    private const val SWITCHES = "com.x.featureswitches.FeatureSwitchesRepositoryImpl"
    private const val FLAG = "cache/birdify-switch-log"
    private val GETTERS = listOf("Boolean", "Int", "Long", "Float", "Double", "String", "List")
        .flatMap { listOf("get$it", "peek$it") }

    private val seen = ConcurrentHashMap<String, String>()

    @Volatile
    private var dumped = false

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!File(lpparam.appInfo.dataDir, FLAG).exists()) return
        try {
            val switches = Class.forName(SWITCHES, false, lpparam.classLoader)
            val targets = GETTERS.sumOf { XposedBridge.hookAllMethods(switches, it, getterHook).size }
            XposedBridge.log("$LOG: switch log hooked $targets getters on $SWITCHES")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: switch log unavailable: $t")
        }
    }

    private fun file(name: String): File? = AndroidAppHelper.currentApplication()?.cacheDir?.let { File(it, name) }

    @Synchronized
    private fun append(name: String, line: String) {
        file(name)?.appendText(line + "\n")
    }

    /**
     * The first frame in X's own code outside the switch store. The hooking
     * framework's frames come first and its classes are obfuscated, so they are
     * skipped by keeping only X's packages rather than by naming them.
     */
    private fun caller(): String = Throwable().stackTrace.firstOrNull {
        val c = it.className
        (c.startsWith("com.x.") || c.startsWith("com.twitter.")) && !c.startsWith("com.x.featureswitches.")
    }?.let { "${it.className}.${it.methodName}" } ?: "?"

    private fun dump(store: Any) {
        dumped = true
        try {
            val all = store.javaClass.getMethod("getFeatureSwitches").invoke(store) as? Map<*, *> ?: return
            val text = all.entries
                .sortedBy { it.key.toString() }
                .joinToString("\n") { "${it.key}\t${it.value}" }
            file("switches-all.tsv")?.writeText(text + "\n")
            XposedBridge.log("$LOG: switch log dumped ${all.size} switches")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: switch dump failed: $t")
        }
    }

    private val getterHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val key = param.args.firstOrNull() as? String ?: return
                val type = param.method.name.removePrefix("get").removePrefix("peek").lowercase()
                val value = "${param.result}"
                if (seen.put("$type:$key", value) == value) return
                val default = param.args.getOrNull(1)?.toString() ?: ""
                append("switches.tsv", "$type\t$key\t$value\t$default\t${caller()}")
                if (!dumped) dump(param.thisObject)
            } catch (t: Throwable) {
                XposedBridge.log("$LOG: switch log failed: $t")
            }
        }
    }
}
