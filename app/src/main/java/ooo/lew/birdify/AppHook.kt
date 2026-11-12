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
 * swaps X logos for the classic bird, the app name back to "Twitter", and
 * rewrites Post/Repost wording to Tweet/Retweet.
 *
 * X renumbers every resource id on each release and no longer ships a bird
 * vector, so the bird is bundled here and X's icon ids are resolved by name at
 * runtime. Nothing in this file may hardcode a resource id or name an
 * obfuscated class; both are invalidated by an X update.
 */
class AppHook {

    companion object {
        const val appPkg = "com.twitter.android"
        private const val LOG = "birdify"

        /** X renamed app_name to APP_NAME in 12.41.0, so try each spelling. */
        val appNames = listOf("APP_NAME", "app_name")
    }

    private val remap = mutableMapOf<Int, Int>()
    private var idAppName = 0
    private var moduleRes: Resources? = null

    @Volatile
    private var resolved = false

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            moduleRes = XModuleResources.createInstance(MainHook.modulePath, null)
        } catch (t: Throwable) {
            log("module resources unavailable: $t")
        }

        try {
            val resourcesClass = Resources::class.java
            val resourcesImplClass =
                XposedHelpers.findClass("android.content.res.ResourcesImpl", lpparam.classLoader)
            for (c in listOf(resourcesClass, resourcesImplClass)) {
                XposedBridge.hookAllMethods(c, "getDrawable", drawableHook)
                XposedBridge.hookAllMethods(c, "getDrawableForDensity", drawableHook)
                XposedBridge.hookAllMethods(c, "getText", textHook)
                XposedBridge.hookAllMethods(c, "getQuantityText", textHook)
            }
            // Compose reads vectors as xml rather than as drawables.
            XposedBridge.hookAllMethods(resourcesClass, "getXml", xmlHook)
            XposedBridge.hookAllMethods(resourcesImplClass, "loadXmlResourceParser", xmlHook)
        } catch (t: Throwable) {
            log("hooks failed: $t")
        }

        Targets.resolve(lpparam)
        DimTheme.hook()
        DimSurfaces.hook()
        BirdTint.hook()
        BirdRefresh.hook()
        Switches.hook(lpparam.classLoader)
        GrokButton.hook()
        TimelineAds.hook()
    }

    private fun log(message: String) = XposedBridge.log("$LOG: $message")

    private fun birdFor(id: Int): Int? = if (remap.containsKey(id)) R.drawable.bird_blue else null

    /**
     * Resolves X's resource names against the running build. Deferred until first
     * resource access because the app's Resources does not exist at hook time.
     */
    private fun ensureResolved(resources: Resources?) {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            XIcons.ids(resources)
            val res = resources ?: XIcons.resources() ?: return
            try {
                idAppName = appNames
                    .map { res.getIdentifier(it, "string", appPkg) }
                    .firstOrNull { it != 0 } ?: 0
                for (id in XIcons.ids(res)) {
                    remap[id] = R.drawable.bird_blue
                }
                for (name in XIcons.missing) {
                    log("$name absent from this X build, leaving it alone")
                }
            } catch (t: Throwable) {
                log("resource resolution failed: $t")
            } finally {
                resolved = true
            }
            log("replacing ${remap.size}/${XIcons.names.size} X icons, appName=${idAppName != 0}")
        }
    }

    private val drawableHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val id = param.args.firstOrNull() as? Int ?: return
            if (id == 0) return
            ensureResolved(param.thisObject as? Resources)
            val bird = birdFor(id) ?: return
            moduleRes?.getDrawable(bird, null)?.let { param.result = it }
        }
    }

    private val xmlHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val id = param.args.firstOrNull() as? Int ?: return
            if (id == 0) return
            ensureResolved(param.thisObject as? Resources)
            val bird = birdFor(id) ?: return
            moduleRes?.getXml(bird)?.let { param.result = it }
        }
    }

    private val textHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (idAppName == 0) ensureResolved(param.thisObject as? Resources)
            if (idAppName != 0 && param.args.firstOrNull() as? Int == idAppName) {
                param.result = "Twitter"
                return
            }
            val original = param.result as? CharSequence ?: return
            val replaced = Words.apply(original.toString())
            if (replaced != original.toString()) param.result = replaced
        }
    }
}
