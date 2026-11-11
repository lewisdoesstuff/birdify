package ooo.lew.birdify

import de.robv.android.xposed.XposedBridge

/**
 * Repaints the app bar, status bar and bottom nav from lights out to dim.
 *
 * DimTheme switches X's theme to its own dim palette, but those bars draw from
 * a second design-system palette whose single dark instance is chosen only by
 * light versus dark, so it stays lights out. Its dark greys live in static
 * colour fields that only that dark instance reads, so swapping them cannot
 * reach light mode.
 *
 * Targets finds the fields the dark instance alone reads, and each grey is
 * picked from those by the value X ships and read back after writing. A
 * changed value leaves that bar lights out and logged rather than repainting
 * something else. The swap has to land before the palette instance is built,
 * which is why it runs when the package loads.
 */
object DimSurfaces {

    private const val LOG = "birdify"

    /** value X ships, classic Twitter dim replacement, role in XColors. */
    private val surfaces = listOf(
        Triple(0xFF030303L, 0xFF15202BL, "backgroundPrimary"),
        Triple(0xFF141414L, 0xFF1C2C3CL, "backgroundSecondary"),
        Triple(0xFF262626L, 0xFF22303CL, "backgroundTertiary"),
    )

    /**
     * Compose holds a Color in a ULong with the packed ARGB in the high word, so
     * the field values are not the plain 0xAARRGGBB literals they are built from.
     */
    private fun colorLong(argb: Long) = argb shl 32

    fun hook() {
        val fields = Targets.fields(Targets.DARK_SURFACES)
        if (fields.isEmpty()) {
            XposedBridge.log("$LOG: dim surfaces have no palette fields, bars unchanged")
            return
        }
        val values = fields.associateWith {
            try {
                it.getLong(null)
            } catch (t: Throwable) {
                XposedBridge.log("$LOG: ${it.name} unreadable: $t")
                null
            }
        }

        var applied = 0
        for ((expectedArgb, replacementArgb, role) in surfaces) {
            val expected = colorLong(expectedArgb)
            val replacement = colorLong(replacementArgb)
            val field = values.entries.filter { it.value == expected }.map { it.key }.singleOrNull()
            if (field == null) {
                XposedBridge.log("$LOG: $role has no single field holding ${hex(expected)}, leaving it alone")
                continue
            }
            field.setLong(null, replacement)
            val after = field.getLong(null)
            if (after == replacement) {
                applied++
            } else {
                XposedBridge.log("$LOG: $role did not take, still ${hex(after)}")
            }
        }
        XposedBridge.log("$LOG: dim surfaces applied $applied/${surfaces.size}")
    }

    private fun hex(value: Long) = "0x" + java.lang.Long.toHexString(value).uppercase()
}
