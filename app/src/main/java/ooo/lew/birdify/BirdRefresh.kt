package ooo.lew.birdify

import android.content.res.Resources
import android.content.res.XModuleResources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import ooo.lew.birdify.R

/**
 * Swaps the home header's refresh animation for an animated bird.
 *
 * X draws the header logo as a Lottie animation that rests on frame 0 and is
 * scrubbed through its pull, loop and release ranges by frame number, so the
 * bundled bird keeps X's canvas, frame rate and frame ranges. Lottie reads the
 * json through openRawResource, which is where it is substituted.
 *
 * X recolours its own layers to the theme by layer name. The bird's layers
 * are named differently, so it keeps its own Twitter blue in light and dark.
 */
object BirdRefresh {

    private const val LOG = "birdify"
    private const val NAME = "x_refresh_logo"

    @Volatile
    private var moduleRes: Resources? = null

    @Volatile
    private var id = 0

    @Volatile
    private var resolved = false

    @Volatile
    private var served = false

    fun hook() {
        try {
            moduleRes = XModuleResources.createInstance(MainHook.modulePath, null)
            XposedBridge.hookAllMethods(Resources::class.java, "openRawResource", rawHook)
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: refresh logo unavailable: $t")
        }
    }

    /** Resolved on first use because the app's Resources does not exist at hook time. */
    private fun resolve(res: Resources) {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            id = res.getIdentifier(NAME, "raw", AppHook.appPkg)
            resolved = true
            if (id == 0) XposedBridge.log("$LOG: $NAME absent from this X build, leaving it alone")
        }
    }

    private val rawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val res = param.thisObject as? Resources ?: return
            val module = moduleRes ?: return
            // Our own read below comes back through this hook, and module ids
            // share X's 0x7f package, so it must not be taken for X's.
            if (res === module) return
            val requested = param.args.firstOrNull() as? Int ?: return
            resolve(res)
            if (id == 0 || requested != id) return
            param.result = module.openRawResource(R.raw.bird_refresh)
            if (!served) {
                served = true
                XposedBridge.log("$LOG: serving the bird for $NAME")
            }
        }
    }
}
