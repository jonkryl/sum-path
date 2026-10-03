package com.jonkryl.sumpath.ads

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import com.jonkryl.sumpath.BuildConfig
import com.jonkryl.sumpath.R
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import kotlin.math.roundToInt

/** A single isolated banner. Ad errors never change a puzzle or block its controls. */
class BannerController(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences("ad_privacy", Activity.MODE_PRIVATE)
    private var host: FrameLayout? = null
    private var banner: BannerAdView? = null
    private var destroyed = false
    private var attached = false
    private var initialized = false
    private var started = true
    private val retryPolicy = BannerRetryPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { loadBanner() }

    fun attach(container: FrameLayout) {
        if (destroyed || attached) return
        attached = true
        host = container
        configurePrivacy()
        YandexAds.initialize(activity.applicationContext) {
            activity.runOnUiThread {
                if (!destroyed) {
                    initialized = true
                    container.post { loadBanner() }
                }
            }
        }
    }

    private fun configurePrivacy() {
        // The persisted choice, initially false, precedes initialization and every request.
        YandexAds.setUserConsent(preferences.getBoolean("personalized", false))
        YandexAds.setLocationTracking(false)
        YandexAds.setAppAdAnalyticsReporting(false)
    }

    private fun loadBanner() {
        val container = host ?: return
        if (destroyed || !initialized || !started || activity.isDestroyed || activity.isFinishing) return
        if (!retryPolicy.tryStart(SystemClock.uptimeMillis())) return
        banner?.let {
            configurePrivacy()
            it.loadAd(AdRequest.Builder(BuildConfig.YANDEX_BANNER_ID).build())
            return
        }
        val density = activity.resources.displayMetrics.density
        val width = ((container.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels) / density).roundToInt().coerceAtLeast(1)
        val size = BannerAdSize.sticky(activity, width)
        container.minimumHeight = maxOf(container.minimumHeight, size.getHeightInPixels(activity))
        val view = BannerAdView(activity)
        banner = view
        view.setAdSize(size)
        view.setBannerAdEventListener(object : BannerAdEventListener {
            override fun onAdLoaded() {
                activity.runOnUiThread {
                    if (destroyed || activity.isDestroyed) view.destroy()
                    else if (banner === view) {
                        retryPolicy.succeeded()
                        handler.removeCallbacks(retry)
                    }
                }
            }
            override fun onAdFailedToLoad(error: AdRequestError) {
                activity.runOnUiThread {
                    if (!destroyed && banner === view) {
                        retryPolicy.failed(SystemClock.uptimeMillis())?.let { delay ->
                            handler.removeCallbacks(retry)
                            if (started) handler.postDelayed(retry, delay)
                        }
                    }
                }
            }
            override fun onAdClicked() = Unit
            override fun onImpression(data: ImpressionData?) = Unit
        })
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        view.loadAd(AdRequest.Builder(BuildConfig.YANDEX_BANNER_ID).build())
    }

    fun showPrivacyChoice() {
        if (destroyed || activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.ad_privacy_title)
            .setMessage(R.string.ad_privacy_message)
            .setPositiveButton(R.string.ad_allow_personalization) { _, _ -> choose(true) }
            .setNegativeButton(R.string.ad_contextual) { _, _ -> choose(false) }
            .setNeutralButton(R.string.ad_policy) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.ad_no_browser, Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun choose(personalized: Boolean) {
        // Both choices allow advertising; this only controls data personalization.
        if (!preferences.edit().putBoolean("personalized", personalized).commit()) return
        configurePrivacy()
        handler.removeCallbacks(retry)
        banner?.destroy()
        banner = null
        retryPolicy.reset()
        host?.removeAllViews()
        loadBanner()
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacks(retry)
        banner?.destroy()
        banner = null
        host = null
    }

    fun onStart() {
        if (destroyed) return
        started = true
        handler.removeCallbacks(retry)
        if (initialized) retryPolicy.remainingDelay(SystemClock.uptimeMillis())?.let {
            handler.postDelayed(retry, it)
        }
    }

    fun onStop() {
        started = false
        handler.removeCallbacks(retry)
    }
}
