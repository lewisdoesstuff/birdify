package ooo.lew.birdify

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Repaints the app bar, status bar and bottom nav from lights out to dim.
 *
 * DimTheme switches X's theme to its own dim palette, but those bars draw from
 * a second design-system palette whose single dark instance is chosen only by
 * light versus dark, so it stays lights out. Its three dark greys live on one
 * colour class and are read only by that dark instance, so swapping them by
 * field cannot reach light mode.
 *
 * The class and field names depend on R8, so each field is checked against
 * the value X currently ships and read back after writing. A rename leaves the
 * bars lights out and logged rather than repainting something else. The swap
 * has to land before the palette instance is built, which is why it runs when
 * the package loads.
 */
object DimSurfaces {

    private const val LOG = "birdify"
    private const val COLOURS = "com.x.xds.core.c"

    /** field name, value X ships, classic Twitter dim replacement. */
    private val surfaces = listOf(
        Triple("c", 0xFF030303L, 0xFF15202BL),
        Triple("v", 0xFF141414L, 0xFF1C2C3CL),
        Triple("u", 0xFF262626L, 0xFF22303CL),
    )

    /**
     * Compose holds a Color in a ULong with the packed ARGB in the high word, so
     * the field values are not the plain 0xAARRGGBB literals they are built from.
     */
    private fun colorLong(argb: Long) = argb shl 32

    fun hook(cl: ClassLoader) {
        val colours = try {
            XposedHelpers.findClass(COLOURS, cl)
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: colours $COLOURS not found, bars unchanged")
            return
        }

        var applied = 0
        for ((field, expectedArgb, replacementArgb) in surfaces) {
            val expected = colorLong(expectedArgb)
            val replacement = colorLong(replacementArgb)
            val current = try {
                XposedHelpers.getStaticLongField(colours, field)
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
            XposedHelpers.setStaticLongField(colours, field, replacement)
            val after = XposedHelpers.getStaticLongField(colours, field)
            if (after == replacement) {
                applied++
            } else {
                XposedBridge.log("$LOG: $field did not take, still ${hex(after)}")
            }
        }
        XposedBridge.log("$LOG: dim surfaces applied $applied/${surfaces.size}")
    }

    private fun hex(value: Long) = "0x" + java.lang.Long.toHexString(value).uppercase()
}
