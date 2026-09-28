package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Paints the substituted bird Twitter blue.
 *
 * X draws its logos through a composable that tints whatever vector it is given
 * to match the surrounding surface, so the bird arrives black in light mode and
 * white in dark mode. Passing a different colour to that composable is the only
 * way past it.
 *
 * The composable is named here because R8 renames it, so both the class and the
 * method are checked when the hook installs. A rename leaves the bird tinted by
 * the theme and says so in the log, rather than changing the app's icons.
 */
object BirdTint {

    private const val LOG = "birdify"
    private const val ICON_COMPOSABLE = "com.x.compose.core.l1"
    private const val ICON_COMPOSABLE_METHOD = "a"
    private const val ICON_ARG = 0
    private const val RES_FIELD = "a"
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
    private var installed = false

    @Volatile
    private var tinted = 0

    fun hook(cl: ClassLoader) {
        try {
            val composable = XposedHelpers.findClass(ICON_COMPOSABLE, cl)
            val targets = XposedBridge.hookAllMethods(composable, ICON_COMPOSABLE_METHOD, tintHook)
            installed = targets.isNotEmpty()
            XposedBridge.log(
                if (installed) "$LOG: bird tint hook installed on $ICON_COMPOSABLE"
                else "$LOG: bird tint found no $ICON_COMPOSABLE_METHOD on $ICON_COMPOSABLE"
            )
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: bird tint unavailable: $t")
        }
    }

    private val tintHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!installed || param.args.size < ARGS) return
            val icons = XIcons.ids()
            if (icons.isEmpty()) return
            val resId = try {
                XposedHelpers.getIntField(param.args[ICON_ARG], RES_FIELD)
            } catch (t: Throwable) {
                return
            }
            if (resId !in icons) return
            if (tinted == 0) {
                XposedBridge.log("$LOG: tinting $ICON_COMPOSABLE.$ICON_COMPOSABLE_METHOD for $resId")
            }
            param.args[DEFAULT_MASK_ARG] =
                (param.args[DEFAULT_MASK_ARG] as Int) and TINT_DEFAULT_BIT.inv()
            param.args[TINT_ARG] = TWITTER_BLUE
            tinted++
        }
    }
}
