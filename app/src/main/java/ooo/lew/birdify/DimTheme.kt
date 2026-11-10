package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Switches X's dark theme from lights out to the classic Twitter dim.
 *
 * X still ships a complete dim palette behind a STANDARD / DIM / LIGHTS_OUT
 * theme enum, but the function that maps the user's setting onto that enum
 * returns LIGHTS_OUT for every dark variant. Rewriting its result to DIM lets
 * X draw dim with its own colours, rather than us repainting individual palette
 * fields that it shares between themes.
 *
 * The mapper and the enum are named here because R8 renames them. The enum's
 * constants keep their names, so the hook checks that the class it found
 * really has DIM and LIGHTS_OUT before installing, and leaves the theme alone
 * and logged otherwise.
 */
object DimTheme {

    private const val LOG = "birdify"
    private const val THEME_MAPPER = "com.x.android.theme.api.j"
    private const val THEME_MAPPER_METHOD = "a"
    private const val THEME_ENUM = "com.x.android.theme.api.k"

    @Volatile
    private var dim: Any? = null

    @Volatile
    private var lightsOut: Any? = null

    @Volatile
    private var swapped = 0

    fun hook(cl: ClassLoader) {
        try {
            val theme = XposedHelpers.findClass(THEME_ENUM, cl)
            val constants = theme.enumConstants?.associateBy { (it as Enum<*>).name }
            if (constants == null) {
                XposedBridge.log("$LOG: $THEME_ENUM is not an enum, dark theme unchanged")
                return
            }
            dim = constants["DIM"]
            lightsOut = constants["LIGHTS_OUT"]
            if (dim == null || lightsOut == null) {
                XposedBridge.log("$LOG: $THEME_ENUM has ${constants.keys}, dark theme unchanged")
                return
            }
            val mapper = XposedHelpers.findClass(THEME_MAPPER, cl)
            val targets = mapper.declaredMethods
                .filter { it.name == THEME_MAPPER_METHOD && it.returnType == theme }
                .map { XposedBridge.hookMethod(it, dimHook) }
            XposedBridge.log(
                if (targets.isNotEmpty()) "$LOG: dim theme hook installed on $THEME_MAPPER"
                else "$LOG: dim theme found no $THEME_MAPPER_METHOD returning $THEME_ENUM"
            )
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
