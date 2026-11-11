package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Drops ads from timelines.
 *
 * X stores timeline entries locally and builds each displayed item from its
 * row through one function, which already returns null for rows it cannot
 * show. Every ad, whether a promoted post, a promoted account or an image ad,
 * carries promoted metadata, so returning null for any item that has some
 * removes the ad without leaving a gap.
 *
 * The function and the metadata class are named here because R8 renames them.
 * The metadata's toString names it, so the first one seen is checked before
 * anything is dropped. Each new kind of item is logged once, which is how a
 * card worth removing can be identified from the device.
 */
object TimelineAds {

    private const val LOG = "birdify"
    private const val ITEM_BUILDER = "org.chromium.support_lib_boundary.util.b"
    private const val ITEM_BUILDER_METHOD = "W"
    private const val ITEM = "com.x.models.timelines.items.o0"
    private const val PROMOTED = "com.x.models.gf"
    private const val PROMOTED_NAME = "TimelinePromotedMetadata("

    private var promoted: Class<*>? = null

    /** Per item class, its fields holding promoted metadata. */
    private val promotedFields = ConcurrentHashMap<Class<*>, List<Field>>()

    @Volatile
    private var confirmed: Boolean? = null

    @Volatile
    private var dropped = 0

    fun hook(cl: ClassLoader) {
        try {
            val item = XposedHelpers.findClass(ITEM, cl)
            promoted = XposedHelpers.findClass(PROMOTED, cl)
            val builder = XposedHelpers.findClass(ITEM_BUILDER, cl)
            val targets = builder.declaredMethods
                .filter { it.name == ITEM_BUILDER_METHOD && it.returnType == item }
                .map { XposedBridge.hookMethod(it, itemHook) }
            XposedBridge.log(
                if (targets.isNotEmpty()) "$LOG: timeline ads hook installed on $ITEM_BUILDER"
                else "$LOG: timeline ads found no $ITEM_BUILDER_METHOD returning $ITEM"
            )
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: timeline ads unavailable: $t")
        }
    }

    private fun fieldsOf(item: Any): List<Field> = promotedFields.getOrPut(item.javaClass) {
        val fields = item.javaClass.declaredFields.filter {
            it.type == promoted && !Modifier.isStatic(it.modifiers)
        }
        fields.forEach { it.isAccessible = true }
        val promotable = if (fields.isEmpty()) "" else ", can be promoted"
        XposedBridge.log("$LOG: timeline item ${describe(item)}$promotable")
        fields
    }

    /** The class name, and the model's own name where its toString gives one. */
    private fun describe(item: Any): String {
        val name = try {
            item.toString().substringBefore('(').take(60)
        } catch (t: Throwable) {
            "?"
        }
        return "${item.javaClass.name} ($name)"
    }

    /** Checks once that the metadata class is still the promoted metadata. */
    private fun confirm(metadata: Any): Boolean {
        confirmed?.let { return it }
        val name = try {
            metadata.toString()
        } catch (t: Throwable) {
            ""
        }
        val ok = name.startsWith(PROMOTED_NAME)
        if (!ok) XposedBridge.log("$LOG: $PROMOTED is not $PROMOTED_NAME, ads unchanged")
        confirmed = ok
        return ok
    }

    private val itemHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val item = param.result ?: return
            for (field in fieldsOf(item)) {
                val metadata = field.get(item) ?: continue
                if (!confirm(metadata)) return
                param.result = null
                if (dropped++ == 0) XposedBridge.log("$LOG: dropping promoted timeline items")
                return
            }
        }
    }
}
