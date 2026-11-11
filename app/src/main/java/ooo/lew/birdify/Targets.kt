package ooo.lew.birdify

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Properties

/**
 * Finds the obfuscated members birdify hooks in the running X build.
 *
 * R8 renames X's classes and members on most releases, so none of them are
 * named here. Each is found by something R8 leaves alone: a toString label, an
 * enum constant's name, or a signature made of unobfuscated types. DexKit
 * searches X's dex for these the first time a build runs, and the answers are
 * cached against the APK and the module, so later launches skip the search.
 *
 * Every search must match exactly once. Anything else is logged and that
 * target is left unresolved, so its hook stays off rather than landing on the
 * wrong member.
 */
object Targets {

    private const val LOG = "birdify"
    private const val CACHE = "birdify-targets.properties"
    private const val CACHE_KEY = "key"

    /** The theme mapper, `(mode, darkVariant, systemIsDark) -> theme enum`. */
    const val THEME_MAPPER = "themeMapper"

    /** The colour fields only the bars' dark palette reads, comma separated. */
    const val DARK_SURFACES = "darkSurfaces"

    /** The composable most X icons are drawn through. */
    const val ICON_COMPOSABLE = "iconComposable"

    /** The icon wrapper's drawable resource id. */
    const val ICON_RES = "iconRes"

    /** AvailablePost's showAskGrokButton. */
    const val ASK_GROK = "askGrok"

    /** The function that builds each timeline item from its stored row. */
    const val ITEM_BUILDER = "itemBuilder"

    /** TimelinePromotedMetadata's class. */
    const val PROMOTED = "promoted"

    private val KEYS = listOf(
        THEME_MAPPER, DARK_SURFACES, ICON_COMPOSABLE, ICON_RES, ASK_GROK, ITEM_BUILDER, PROMOTED,
    )

    private var found: Map<String, String> = emptyMap()
    private lateinit var cl: ClassLoader

    fun resolve(lpparam: XC_LoadPackage.LoadPackageParam) {
        cl = lpparam.classLoader
        val apk = File(lpparam.appInfo.sourceDir)
        val module = MainHook.modulePath?.let { File(it) }
        val key = "${apk.length()}:${apk.lastModified()}:${module?.lastModified()}"
        val cache = File(lpparam.appInfo.dataDir, "cache/$CACHE")

        load(cache)?.takeIf { it[CACHE_KEY] == key }?.let {
            found = it - CACHE_KEY
            log("targets cached: ${summary()}")
            return
        }

        val started = System.nanoTime()
        found = try {
            System.loadLibrary("dexkit")
            DexKitBridge.create(apk.path).use { search(it) }
        } catch (t: Throwable) {
            log("target search unavailable: $t")
            return
        }
        log("targets found in ${(System.nanoTime() - started) / 1_000_000} ms: ${summary()}")
        save(cache, found + (CACHE_KEY to key))
    }

    private fun summary() = "${KEYS.count { !found[it].isNullOrEmpty() }}/${KEYS.size}" +
        KEYS.filter { found[it].isNullOrEmpty() }.let { if (it.isEmpty()) "" else ", missing $it" }

    fun method(key: String): Method? = found[key]?.takeIf { it.isNotEmpty() }?.let {
        try {
            DexMethod(it).getMethodInstance(cl)
        } catch (t: Throwable) {
            log("$key unloadable: $t")
            null
        }
    }

    fun field(key: String): Field? = fields(key).singleOrNull()

    fun fields(key: String): List<Field> = found[key]?.takeIf { it.isNotEmpty() }?.split(',')?.mapNotNull {
        try {
            DexField(it).getFieldInstance(cl).apply { isAccessible = true }
        } catch (t: Throwable) {
            log("$key unloadable: $t")
            null
        }
    } ?: emptyList()

    fun type(key: String): Class<*>? = found[key]?.takeIf { it.isNotEmpty() }?.let {
        try {
            Class.forName(it, false, cl)
        } catch (t: Throwable) {
            log("$key unloadable: $t")
            null
        }
    }

    private fun search(bridge: DexKitBridge): Map<String, String> {
        val out = mutableMapOf<String, String>()
        fun put(key: String, find: () -> String?) {
            out[key] = try {
                find() ?: ""
            } catch (t: Throwable) {
                log("$key search failed: $t")
                ""
            }
        }
        put(THEME_MAPPER) { themeMapper(bridge)?.descriptor }
        put(DARK_SURFACES) { darkSurfaces(bridge)?.joinToString(",") { it.descriptor } }
        put(ICON_COMPOSABLE) { iconComposable(bridge)?.descriptor }
        put(ICON_RES) { labelledField(bridge, "Icon(drawableRes=", "drawableRes")?.descriptor }
        put(ASK_GROK) { labelledField(bridge, "AvailablePost(", "showAskGrokButton")?.descriptor }
        put(PROMOTED) { toStringOf(bridge, "TimelinePromotedMetadata(")?.className }
        put(ITEM_BUILDER) { itemBuilder(bridge)?.descriptor }
        return out
    }

    private fun <T> single(what: String, results: List<T>): T? {
        if (results.size == 1) return results[0]
        log("$what matched ${results.size} times")
        return null
    }

    /** The toString of the data class whose output starts with [prefix]. */
    private fun toStringOf(bridge: DexKitBridge, prefix: String): MethodData? = single(
        "toString printing $prefix",
        bridge.findMethod {
            matcher {
                name("toString")
                usingStrings(prefix)
            }
        }.filter { m -> m.usingStrings.any { it.startsWith(prefix) } },
    )

    /**
     * The field a data class prints after [name]. Kotlin's toString reads each
     * property once, in the order it prints them, so the n-th label belongs to
     * the n-th field read. The counts have to agree for that to hold.
     */
    private fun labelledField(bridge: DexKitBridge, prefix: String, name: String): FieldData? {
        val toString = toStringOf(bridge, prefix) ?: return null
        val labels = toString.usingStrings
            .filter { it.endsWith("=") }
            .map { it.removeSuffix("=").substringAfterLast(", ").substringAfterLast('(') }
        val reads = toString.usingFields
            .filter { it.usingType == FieldUsingType.Read && it.field.className == toString.className }
            .map { it.field }
        if (labels.size != reads.size) {
            log("$prefix toString prints ${labels.size} labels but reads ${reads.size} fields")
            return null
        }
        val index = labels.indexOf(name)
        if (index < 0) {
            log("$prefix toString has no $name label")
            return null
        }
        return reads[index]
    }

    /**
     * The function choosing X's theme enum. The enum keeps its constants' names,
     * and the mapper is the one method taking two values and a boolean that
     * returns it and reads LIGHTS_OUT.
     */
    private fun themeMapper(bridge: DexKitBridge): MethodData? {
        val theme = single(
            "theme enum",
            bridge.findClass {
                matcher {
                    superClass("java.lang.Enum")
                    addFieldForName("STANDARD")
                    addFieldForName("DIM")
                    addFieldForName("LIGHTS_OUT")
                }
            },
        ) ?: return null
        return single(
            "theme mapper",
            bridge.findMethod {
                matcher {
                    returnType(theme.name)
                    paramTypes(null, null, "boolean")
                    addUsingField("${theme.descriptor}->LIGHTS_OUT:${theme.descriptor}", UsingType.Read)
                }
            },
        )
    }

    /**
     * The static colours the bars' palette (XColors) reads while building its
     * dark instance, kept only where that is their sole reader, so changing one
     * cannot reach anything else.
     */
    private fun darkSurfaces(bridge: DexKitBridge): List<FieldData>? {
        val palette = toStringOf(bridge, "XColors(") ?: return null
        val init = single(
            "XColors static initialiser",
            palette.declaredClass!!.methods.filter { it.isStaticInitializer },
        ) ?: return null
        return init.usingFields
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .filter {
                it.typeName == "long" && Modifier.isStatic(it.modifiers) &&
                    it.className != palette.className &&
                    it.readers.all { reader -> reader.descriptor == init.descriptor }
            }
            .distinctBy { it.descriptor }
            .ifEmpty { null }
    }

    /**
     * `(icon, contentDescription, Modifier, tint, enabled, Composer, changed,
     * default)`, where icon is the class printing "Icon(drawableRes=".
     */
    private fun iconComposable(bridge: DexKitBridge): MethodData? {
        val icon = toStringOf(bridge, "Icon(drawableRes=") ?: return null
        return single(
            "icon composable",
            bridge.findMethod {
                matcher {
                    paramTypes(
                        icon.className, "java.lang.String", "androidx.compose.ui.Modifier", "long",
                        "boolean", "androidx.compose.runtime.Composer", "int", "int",
                    )
                    returnType("void")
                }
            },
        )
    }

    /**
     * The static function taking a stored row and two LinkedHashMaps that
     * constructs UrtTimelinePost. It returns the timeline item interface.
     */
    private fun itemBuilder(bridge: DexKitBridge): MethodData? {
        val post = toStringOf(bridge, "UrtTimelinePost(") ?: return null
        return single(
            "timeline item builder",
            bridge.findMethod {
                matcher {
                    paramTypes(null, "java.util.LinkedHashMap", "java.util.LinkedHashMap", null)
                    addInvoke {
                        declaredClass(post.className)
                        name("<init>")
                    }
                }
            },
        )
    }

    private fun load(file: File): Map<String, String>? = try {
        if (!file.exists()) null
        else Properties().apply { file.inputStream().use { load(it) } }
            .let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
    } catch (t: Throwable) {
        null
    }

    private fun save(file: File, values: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { out -> Properties().apply { putAll(values) }.store(out, null) }
        } catch (t: Throwable) {
            log("target cache unwritable: $t")
        }
    }

    private fun log(message: String) = XposedBridge.log("$LOG: $message")
}
