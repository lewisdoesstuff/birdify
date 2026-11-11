package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field

/**
 * Paints the substituted bird Twitter blue.
 *
 * X draws its logos through a composable that tints whatever vector it is given
 * to match the surrounding surface, so the bird arrives black in light mode and
 * white in dark mode. Passing a different colour to that composable is the only
 * way past it.
 *
 * Targets finds the composable and the icon wrapper's resource field, since R8
 * renames both. If either is missing the bird stays tinted by the theme and
 * the log says so, rather than this changing the app's icons.
 */
object BirdTint {

    private const val LOG = "birdify"
    private const val ICON_ARG = 0
    private const val TINT_ARG = 3
    private const val DEFAULT_MASK_ARG = 7
    private const val ARGS = 8

    /**
     * The tint is a defaulted parameter, and the last int is Compose's default
     * mask with one bit per parameter. Bit 3 is the tint, and while it is set
     * the callee substitutes LocalContentColor and discards the value passed
     * here, so the bit has to be cleared for the colour to take effect.
     */
    private const val TINT_DEFAULT_BIT = 1 shl TINT_ARG

    /** Compose packs a Color into a ULong with the ARGB in the high word. */
    private val TWITTER_BLUE = 0xFF1D9BF0L shl 32

    @Volatile
    private var resField: Field? = null

    @Volatile
    private var tinted = 0

    fun hook() {
        try {
            val composable = Targets.method(Targets.ICON_COMPOSABLE)
            val res = Targets.field(Targets.ICON_RES)
            if (composable == null || res == null || res.type != Int::class.javaPrimitiveType) {
                XposedBridge.log("$LOG: bird tint has no icon composable, bird unchanged")
                return
            }
            resField = res
            XposedBridge.hookMethod(composable, tintHook)
            XposedBridge.log("$LOG: bird tint hook installed on ${composable.declaringClass.name}.${composable.name}")
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: bird tint unavailable: $t")
        }
    }

    private val tintHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val res = resField ?: return
            if (param.args.size < ARGS) return
            val icons = XIcons.ids()
            if (icons.isEmpty()) return
            val resId = try {
                res.getInt(param.args[ICON_ARG])
            } catch (t: Throwable) {
                return
            }
            if (resId !in icons) return
            if (tinted == 0) XposedBridge.log("$LOG: tinting the bird, resource $resId")
            param.args[DEFAULT_MASK_ARG] =
                (param.args[DEFAULT_MASK_ARG] as Int) and TINT_DEFAULT_BIT.inv()
            param.args[TINT_ARG] = TWITTER_BLUE
            tinted++
        }
    }
}
