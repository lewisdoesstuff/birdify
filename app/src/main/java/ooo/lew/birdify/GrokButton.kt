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
 * R8 renames the model and the field, so Targets finds the field from the
 * bytecode of the model's toString: it is the one read for the
 * showAskGrokButton label. The toString itself cannot be called to check
 * this, since it throws on a function reference whose metadata R8 stripped.
 * If the field is missing or not a boolean, the button is left alone and logged.
 */
object GrokButton {

    private const val LOG = "birdify"

    @Volatile
    private var flag: Field? = null

    @Volatile
    private var hidden = 0

    fun hook() {
        try {
            val field = Targets.field(Targets.ASK_GROK) ?: run {
                XposedBridge.log("$LOG: grok button has no showAskGrokButton field, button unchanged")
                return
            }
            val model = field.declaringClass
            if (field.type != Boolean::class.javaPrimitiveType) {
                XposedBridge.log("$LOG: ${model.name}.${field.name} is ${field.type}, button unchanged")
                return
            }
            flag = field
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
            val field = flag ?: return
            if (!field.getBoolean(post)) return
            field.setBoolean(post, false)
            if (hidden++ == 0) XposedBridge.log("$LOG: grok button hidden")
        }
    }
}
