package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * Removes the "Explain this post with Grok" button from posts.
 *
 * The server marks each post that should offer the button, and X copies that
 * onto the post's UI model as showAskGrokButton. Clearing it as each model is
 * built hides the button wherever posts are drawn.
 *
 * The model and the field are named here because R8 renames them. The model's
 * toString cannot be used to confirm the field at runtime, since it throws on
 * a function reference whose metadata R8 stripped, so resync.sh confirms it
 * from the bytecode instead: the boolean toString prints after its
 * showAskGrokButton label must be this field. A rename leaves the button alone
 * and logged.
 */
object GrokButton {

    private const val LOG = "birdify"
    private const val POST_MODEL = "com.x.urt.items.post.a6"
    private const val SHOW_ASK_GROK = "Y"

    @Volatile
    private var flag: Field? = null

    @Volatile
    private var hidden = 0

    fun hook(cl: ClassLoader) {
        try {
            val model = XposedHelpers.findClass(POST_MODEL, cl)
            val field = model.getDeclaredField(SHOW_ASK_GROK)
            if (field.type != Boolean::class.javaPrimitiveType) {
                XposedBridge.log("$LOG: $POST_MODEL.$SHOW_ASK_GROK is ${field.type}, button unchanged")
                return
            }
            field.isAccessible = true
            flag = field
            val targets = XposedBridge.hookAllConstructors(model, modelHook)
            XposedBridge.log(
                if (targets.isNotEmpty()) "$LOG: grok button hook installed on $POST_MODEL"
                else "$LOG: grok button found no constructor on $POST_MODEL"
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
