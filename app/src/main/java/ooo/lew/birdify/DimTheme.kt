package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge

/**
 * Switches X's dark theme from lights out to the classic Twitter dim.
 *
 * X still ships a complete dim palette behind a STANDARD / DIM / LIGHTS_OUT
 * theme enum, but the function that maps the user's setting onto that enum
 * returns LIGHTS_OUT for every dark variant. Rewriting its result to DIM lets
 * X draw dim with its own colours, rather than us repainting individual palette
 * fields that it shares between themes.
 *
 * Targets finds the mapper, since R8 renames it and the enum. The enum's
 * constants keep their names, so the hook checks that the mapper's result
 * really has DIM and LIGHTS_OUT before installing, and leaves the theme alone
 * and logged otherwise.
 */
object DimTheme {

    private const val LOG = "birdify"

    @Volatile
    private var dim: Any? = null

    @Volatile
    private var lightsOut: Any? = null

    @Volatile
    private var swapped = 0

    fun hook() {
        try {
            val mapper = Targets.method(Targets.THEME_MAPPER) ?: run {
                XposedBridge.log("$LOG: dim theme has no mapper, dark theme unchanged")
                return
            }
            val theme = mapper.returnType
            val constants = theme.enumConstants?.associateBy { (it as Enum<*>).name }
            if (constants == null) {
                XposedBridge.log("$LOG: ${theme.name} is not an enum, dark theme unchanged")
                return
            }
            dim = constants["DIM"]
            lightsOut = constants["LIGHTS_OUT"]
            if (dim == null || lightsOut == null) {
                XposedBridge.log("$LOG: ${theme.name} has ${constants.keys}, dark theme unchanged")
                return
            }
            XposedBridge.hookMethod(mapper, dimHook)
            XposedBridge.log("$LOG: dim theme hook installed on ${mapper.declaringClass.name}.${mapper.name}")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: dim theme unavailable: $t")
        }
    }

    private val dimHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.result !== lightsOut) return
            param.result = dim
            if (swapped++ == 0) XposedBridge.log("$LOG: lights out replaced with dim")
        }
    }
}
