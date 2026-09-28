package ooo.lew.birdify

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Replaces X's dark surface colours with the classic Twitter dim palette.
 *
 * X keeps every theme's colours as static longs on one palette class, so the
 * replacement is by field: swapping by value would also catch the pure black
 * used for text in light mode. Only the class and field names depend on R8,
 * so each field is checked against the value X currently ships and read back
 * after writing. A rename leaves the module inert and logged rather than
 * silently repainting the app.
 */
object DarkTokens {

    private const val LOG = "birdify"
    private const val PALETTE = "com.x.compose.core.i3"

    /** field name, value X ships, Twitter dim replacement. */
    private val tokens = listOf(
        Triple("J1", 0xFF030303L, 0xFF15202BL),
        Triple("A", 0xFF0F1419L, 0xFF15202BL),
        Triple("G", 0xFF141414L, 0xFF1C2C3CL),
    )

    /**
     * Compose holds a Color in a ULong with the packed ARGB in the high word, so
     * the field values are not the plain 0xAARRGGBB literals they are built from.
     */
    private fun colorLong(argb: Long) = argb shl 32

    fun hook(cl: ClassLoader) {
        val palette = try {
            XposedHelpers.findClass(PALETTE, cl)
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: palette $PALETTE not found, dark theme unchanged")
            return
        }

        var applied = 0
        for ((field, expectedArgb, replacementArgb) in tokens) {
            val expected = colorLong(expectedArgb)
            val replacement = colorLong(replacementArgb)
            val current = try {
                XposedHelpers.getStaticLongField(palette, field)
            } catch (t: Throwable) {
                XposedBridge.log("$LOG: $field unreadable: $t")
                continue
            }
            if (current != expected) {
                XposedBridge.log(
                    "$LOG: $field is ${hex(current)}, expected ${hex(expected)}, leaving it alone"
                )
                continue
            }
            XposedHelpers.setStaticLongField(palette, field, replacement)
            val after = XposedHelpers.getStaticLongField(palette, field)
            if (after == replacement) {
                applied++
            } else {
                XposedBridge.log("$LOG: $field did not take, still ${hex(after)}")
            }
        }
        XposedBridge.log("$LOG: dim dark tokens applied $applied/${tokens.size}")
    }

    private fun hex(value: Long) = "0x" + java.lang.Long.toHexString(value).uppercase()
}
