package ooo.lew.birdify

import android.content.Context
import android.content.res.Resources

/** The X drawables birdify replaces with the bird, resolved by name. */
object XIcons {

    val names = listOf(
        "ic_vector_x",
        "ic_vector_x_card",
        "ic_vector_x_circle",
        "ic_logo_x",
    )

    @Volatile
    var missing: List<String> = emptyList()
        private set

    @Volatile
    private var ids: Set<Int> = emptySet()

    @Volatile
    private var source: Resources? = null

    @Volatile
    private var resolved = false

    /**
     * Resolves the icon names against the app's own resources so the ids stay
     * correct across X releases. Callers may pass a Resources they already hold.
     */
    fun ids(resources: Resources? = null): Set<Int> {
        if (resolved) return ids
        synchronized(this) {
            if (resolved) return ids
            val res = resources ?: appContext()?.resources ?: return emptySet()
            val found = mutableSetOf<Int>()
            val absent = mutableListOf<String>()
            for (name in names) {
                val id = res.getIdentifier(name, "drawable", AppHook.appPkg)
                if (id == 0) absent.add(name) else found.add(id)
            }
            ids = found
            missing = absent
            source = res
            resolved = true
            return ids
        }
    }

    /** The app Resources, available once ids() has resolved. */
    fun resources(): Resources? {
        if (!resolved) ids()
        return source
    }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (t: Throwable) {
        null
    }
}
