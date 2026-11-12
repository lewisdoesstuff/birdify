package ooo.lew.birdify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns off X features that are gated behind a feature switch.
 *
 * X reads a switch either with get, which records an impression, or with peek,
 * which does not, so both are answered. Each switch here defaults to off in
 * X's own code, so answering false makes X behave as if the server had never
 * turned it on. The switch store keeps its real names through R8, so nothing
 * here depends on an obfuscated symbol. resync.sh checks every key still
 * exists in X.
 */
object Switches {

    private const val LOG = "birdify"
    private const val SWITCHES = "com.x.featureswitches.FeatureSwitchesRepositoryImpl"

    /** Every switch answered false, grouped by what it removes. */
    val OFF = setOf(
        // Grok: the nav tab and its badge, the bot in the side menu, and the
        // Imagine and download prompts on posts, media and the photo editor.
        "android_webview_grok_tab_enabled",
        "grok_android_tab_badge_enabled",
        "android_grok_bot_sidebar_enabled",
        "grok_android_imagine_cta_enabled",
        "grok_android_download_grok_cta_enabled",
        "grok_android_imagine_create_your_own_enabled",
        "android_xlite_photo_editor_grok_imagine_enabled",

        // Programmatic ads X places itself through Google. They never pass
        // through the timeline item builder, so TimelineAds cannot see them.
        "android_x_lite_ssp_ads_enabled",
        "ssp_ads_home_enabled",
        "ssp_ads_tweet_details",
        "ssp_ads_immersive",
        "ssp_ads_profile",
        "ssp_ads_spotlight",

        // Premium upsells: the header's Upgrade pill, the paywall on launch,
        // the drawer, profile and composer prompts, and Boost this post.
        "subscriptions_upsells_premium_home_nav_enabled",
        "subscriptions_upsells_home_nav_follow_button_enabled",
        "premium_paywall_on_app_load_journey_enabled",
        "subscriptions_upsells_drawer_discount_enabled",
        "subscriptions_upsells_get_verified_drawer_card_enabled",
        "subscriptions_upsells_get_verified_profile",
        "subscriptions_upsells_get_verified_profile_card",
        "subscriptions_upsells_verified_profile_visitor_upsell_enabled",
        "subscriptions_upsell_visitor_verified_profile",
        "subscriptions_upsells_analytics_profile_enabled",
        "subscriptions_upsells_bookmark_folders_enabled",
        "subscriptions_upsells_long_video_upload_composer_enabled",
        "subscriptions_upsells_articles_post_composer_promo_variant_enabled",
        "x_lite_quick_promote_enabled",
        "x_lite_quick_promote_nux_enabled",
    )

    private val answered = ConcurrentHashMap.newKeySet<String>()

    fun hook(cl: ClassLoader) {
        try {
            val switches = Class.forName(SWITCHES, false, cl)
            val targets = listOf("getBoolean", "peekBoolean")
                .sumOf { XposedBridge.hookAllMethods(switches, it, switchHook).size }
            XposedBridge.log(
                if (targets > 0) "$LOG: switch hooks installed on $SWITCHES ($targets)"
                else "$LOG: switches found no getBoolean or peekBoolean on $SWITCHES"
            )
        } catch (t: Throwable) {
            XposedBridge.log("$LOG: switches unavailable: $t")
        }
    }

    private val switchHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val key = param.args.firstOrNull() as? String ?: return
            if (key !in OFF) return
            param.result = false
            if (answered.add(key)) XposedBridge.log("$LOG: switch $key answered false")
        }
    }
}
