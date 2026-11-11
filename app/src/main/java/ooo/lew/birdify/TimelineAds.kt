package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
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
 * Targets finds the function and the metadata class, since R8 renames them.
 * Each new kind of item is logged once, which is how a card worth removing can
 * be identified from the device.
 */
object TimelineAds {

    private const val LOG = "birdify"

    private var promoted: Class<*>? = null

    /** Per item class, its fields holding promoted metadata. */
    private val promotedFields = ConcurrentHashMap<Class<*>, List<Field>>()

    @Volatile
    private var dropped = 0

    fun hook() {
        try {
            val builder = Targets.method(Targets.ITEM_BUILDER)
            promoted = Targets.type(Targets.PROMOTED)
            if (builder == null || promoted == null) {
                XposedBridge.log("$LOG: timeline ads has no item builder, ads unchanged")
                return
            }
            XposedBridge.hookMethod(builder, itemHook)
            XposedBridge.log("$LOG: timeline ads hook installed on ${builder.declaringClass.name}.${builder.name}")
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

    private val itemHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val item = param.result ?: return
            if (fieldsOf(item).none { it.get(item) != null }) return
            param.result = null
            if (dropped++ == 0) XposedBridge.log("$LOG: dropping promoted timeline items")
        }
    }
}
