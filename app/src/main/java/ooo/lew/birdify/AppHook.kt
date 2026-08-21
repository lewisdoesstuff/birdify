package ooo.lew.birdify

import android.content.res.Resources
import android.content.res.XModuleResources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ooo.lew.birdify.R

/**
 * Hooks the com.twitter.android process to restore classic Twitter branding:
 * swaps the X header logo for a blue bird, restores the "dim" dark palette,
 * and rewrites "Post"/"Repost" wording back to "Tweet"/"Retweet".
 */
class AppHook {

    companion object {
        const val appPkg = "com.twitter.android"
    }

    private val IC_VECTOR_X = 0x7f0808d8

    private var idTwitterLogo = 0
    private var idLogoX = 0
    private var idVectorX = 0
    private var idVectorXCard = 0
    private var idVectorXCircle = 0
    private var idAppName = 0
    private var resolved = false

    private var moduleRes: Resources? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        try {
            moduleRes = XModuleResources.createInstance(MainHook.modulePath, null)
        } catch (t: Throwable) {
            XposedBridge.log("birdify: failed to load module resources: $t")
        }

        hookResources(cl)
        overrideThemeColors(cl)
    }

    private fun overrideThemeColors(cl: ClassLoader) {
        try {
            val i3 = XposedHelpers.findClass("com.x.compose.core.i3", cl)
            XposedHelpers.setStaticLongField(i3, "J1", colorLong(0xFF15202BL)) // main bg
            XposedHelpers.setStaticLongField(i3, "A", colorLong(0xFF15202BL))   // surface
            XposedHelpers.setStaticLongField(i3, "G", colorLong(0xFF1C2C3CL))   // elevated
        } catch (t: Throwable) {
            XposedBridge.log("birdify: theme override failed: $t")
        }
    }

    private fun colorLong(argb: Long): Long = argb shl 32

    private fun hookResources(cl: ClassLoader) {
        try {
            val resourcesClass = Resources::class.java
            val resourcesImplClass = XposedHelpers.findClass(
                "android.content.res.ResourcesImpl", cl
            )
            val targets = listOf(resourcesClass, resourcesImplClass)
            for (c in targets) {
                XposedBridge.hookAllMethods(c, "getDrawable", drawableHook)
                XposedBridge.hookAllMethods(c, "getDrawableForDensity", drawableHook)
                XposedBridge.hookAllMethods(c, "getIdentifier", identifierHook)
                XposedBridge.hookAllMethods(c, "getText", textHook)
                XposedBridge.hookAllMethods(c, "getQuantityText", textHook)
            }
            // Compose loads vectors through Resources.getXml, not getDrawable.
            XposedBridge.hookAllMethods(resourcesClass, "getXml", xmlHook)
            XposedBridge.hookAllMethods(resourcesImplClass, "loadXmlResourceParser", xmlHook)
        } catch (t: Throwable) {
            XposedBridge.log("birdify: resource hooks failed: $t")
        }

        try {
            val l1Class = XposedHelpers.findClass("com.x.compose.core.l1", cl)
            XposedBridge.hookAllMethods(l1Class, "a", iconComposableHook)
        } catch (t: Throwable) {
            XposedBridge.log("birdify: icon hook failed: $t")
        }
    }

    private val iconComposableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.size < 8) return
            val icon = param.args[0] ?: return
            val resId = try {
                XposedHelpers.getIntField(icon, "a")
            } catch (t: Throwable) {
                -1
            }
            if (resId != IC_VECTOR_X) return
            param.args[3] = colorLong(0xFF1D9BF0L)
            param.args[7] = (param.args[7] as Int) and 0x8.inv()
        }
    }

    @Volatile
    private var resolving = false

    private fun ensureResolved(obj: Any?) {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            resolving = true
            try {
                val res = obj as? Resources
                val get: (String, String) -> Int = if (res != null) { n, t ->
                    res.getIdentifier(n, t, appPkg)
                } else {
                    { n, t -> XposedHelpers.callMethod(obj, "getIdentifier", n, t, appPkg) as Int }
                }
                idTwitterLogo = get("ic_twitter_logo", "drawable")
                idLogoX = get("ic_logo_x", "drawable")
                idVectorX = get("ic_vector_x", "drawable")
                idVectorXCard = get("ic_vector_x_card", "drawable")
                idVectorXCircle = get("ic_vector_x_circle", "drawable")
                idAppName = get("app_name", "string")
            } catch (t: Throwable) {
                // fall back to hardcoded ids below
            } finally {
                resolving = false
            }
            if (idTwitterLogo == 0) applyFallbackIds()
            resolved = true
        }
    }

    private fun applyFallbackIds() {
        idTwitterLogo = 0x7f080477
        idLogoX = 0x7f0803c1
        idVectorX = 0x7f0808d8
        idVectorXCard = 0x7f0808d9
        idVectorXCircle = 0x7f0808da
        idAppName = 0x7f150188
    }

    private fun remap(id: Int): Int = when (id) {
        idLogoX, idVectorXCard, idVectorXCircle -> idTwitterLogo
        else -> id
    }

    private fun moduleDrawableId(id: Int): Int = if (id == idVectorX) R.drawable.bird_black else 0

    private val drawableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.isEmpty()) return
            val id = param.args[0] as? Int ?: return
            if (id == 0) return
            ensureResolved(param.thisObject)

            val moduleId = moduleDrawableId(id)
            if (moduleId != 0) {
                val d = moduleRes?.getDrawable(moduleId, null)
                if (d != null) {
                    param.result = d
                    return
                }
            }

            val newId = remap(id)
            if (newId != id) param.args[0] = newId
        }
    }

    private val xmlHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.isEmpty()) return
            val id = param.args[0] as? Int ?: return
            if (id == 0) return
            ensureResolved(param.thisObject)

            val moduleId = moduleDrawableId(id)
            if (moduleId != 0) {
                val parser = moduleRes?.getXml(moduleId)
                if (parser != null) {
                    param.result = parser
                    return
                }
            }

            val newId = remap(id)
            if (newId != id) param.args[0] = newId
        }
    }

    private val identifierHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (resolving) return
            if (param.args.size < 2) return
            val defType = param.args[1] as? String ?: return
            if (defType != "drawable") return
            val name = param.args[0] as? String ?: return
            val mapped = when (name) {
                "ic_logo_x", "ic_vector_x", "ic_vector_x_card", "ic_vector_x_circle" ->
                    "ic_twitter_logo"
                else -> name
            }
            if (mapped != name) param.args[0] = mapped
        }
    }

    private val textHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.args.isEmpty()) return
            ensureResolved(param.thisObject)
            val id = param.args[0] as? Int ?: return
            if (id == idAppName && idAppName != 0) {
                param.result = "Twitter"
                return
            }
            val orig = param.result as? CharSequence ?: return
            val replaced = Words.apply(orig.toString())
            if (replaced != orig.toString()) param.result = replaced
        }
    }
}
