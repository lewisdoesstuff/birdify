package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field

/**
 * Removes the "Explain this post with Grok" button from posts.
 *
 * The server marks each post that should offer the button, and X copies that
 * onto the post's UI model as showAskGrokButton. Clearing it as each model is
 * built hides the button wherever posts are drawn.
 *
 * R8 renames the model and the field. Targets finds the model by its toString
 * label, and the field as the first model is built: it is the boolean the
 * toString prints after showAskGrokButton (see Targets.printedField). If
 * either is missing, the button is left alone and logged.
 */
object GrokButton {

    private const val LOG = "birdify"

    private var flag: Lazy<Field?> = lazyOf(null)

    @Volatile
    private var hidden = 0

    fun hook() {
        try {
            val model = Targets.type(Targets.AVAILABLE_POST) ?: run {
                XposedBridge.log("$LOG: grok button has no AvailablePost, button unchanged")
                return
            }
            flag = lazy {
                Targets.printedField(model, "showAskGrokButton", Boolean::class.javaPrimitiveType!!).also {
                    XposedBridge.log(
                        if (it != null) "$LOG: grok button clears ${model.name}.${it.name}"
                        else "$LOG: grok button has no showAskGrokButton field, button unchanged"
                    )
                }
            }
            val targets = XposedBridge.hookAllConstructors(model, modelHook)
            XposedBridge.log(
                if (targets.isNotEmpty()) "$LOG: grok button hook installed on ${model.name}"
                else "$LOG: grok button found no constructor on ${model.name}"
            )
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: grok button unavailable: $t")
        }
    }

    private val modelHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val post = param.thisObject ?: return
            val field = flag.value ?: return
            if (!field.getBoolean(post)) return
            field.setBoolean(post, false)
            if (hidden++ == 0) XposedBridge.log("$LOG: grok button hidden")
        }
    }
}
