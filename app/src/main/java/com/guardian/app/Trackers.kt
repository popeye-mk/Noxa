package com.guardian.app

/**
 * Phase 3 — turns a blocked domain into a plain-language "who and why".
 *
 * A compact, embedded map of the trackers responsible for the large majority of
 * real-world blocks (tracking traffic is very concentrated among a few big
 * players). Each entry reads "Company · Category". Lookups check the domain and
 * each parent suffix, so `ssl.google-analytics.com` resolves via
 * `google-analytics.com`. Unknown domains fall back to their root domain so the
 * user still sees *something* honest rather than a raw sub-domain string.
 *
 * No network, no training data — just a table. That's the whole Phase-3a idea.
 */
object Trackers {

    private val MAP: Map<String, String> = mapOf(
        // Google
        "doubleclick.net" to "Google · Advertising",
        "google-analytics.com" to "Google · Analytics",
        "googlesyndication.com" to "Google · Advertising",
        "googleadservices.com" to "Google · Advertising",
        "googletagmanager.com" to "Google · Tag manager",
        "googletagservices.com" to "Google · Advertising",
        "admob.com" to "Google · Advertising (apps)",
        "app-measurement.com" to "Google Firebase · Analytics",
        "crashlytics.com" to "Google Firebase · Diagnostics",
        // Meta
        "connect.facebook.net" to "Meta · Advertising",
        "graph.facebook.com" to "Meta · Analytics",
        "facebook.com" to "Meta · Social/Tracking",
        "fbcdn.net" to "Meta · Tracking",
        // Amazon / big DSPs
        "amazon-adsystem.com" to "Amazon · Advertising",
        "adsrvr.org" to "The Trade Desk · Advertising",
        "adnxs.com" to "Xandr (AppNexus) · Advertising",
        "casalemedia.com" to "Index Exchange · Advertising",
        "openx.net" to "OpenX · Advertising",
        "rubiconproject.com" to "Magnite · Advertising",
        "pubmatic.com" to "PubMatic · Advertising",
        "3lift.com" to "TripleLift · Advertising",
        "bidswitch.net" to "BidSwitch · Advertising",
        "smartadserver.com" to "Smart · Advertising",
        // Measurement / analytics
        "scorecardresearch.com" to "Comscore · Analytics",
        "imrworldwide.com" to "Nielsen · Analytics",
        "quantserve.com" to "Quantcast · Analytics",
        "quantcount.com" to "Quantcast · Analytics",
        "chartbeat.com" to "Chartbeat · Analytics",
        "hotjar.com" to "Hotjar · Session recording",
        "mixpanel.com" to "Mixpanel · Analytics",
        "amplitude.com" to "Amplitude · Analytics",
        "segment.com" to "Segment · Data/Analytics",
        "segment.io" to "Segment · Data/Analytics",
        "flurry.com" to "Yahoo (Flurry) · Analytics",
        "localytics.com" to "Localytics · Analytics",
        // Content recommendation ads
        "taboola.com" to "Taboola · Advertising",
        "outbrain.com" to "Outbrain · Advertising",
        // Mobile attribution
        "appsflyer.com" to "AppsFlyer · Attribution",
        "adjust.com" to "Adjust · Attribution",
        "branch.io" to "Branch · Attribution",
        "app.link" to "Branch · Attribution",
        "kochava.com" to "Kochava · Attribution",
        "singular.net" to "Singular · Attribution",
        "tenjin.io" to "Tenjin · Attribution",
        // Mobile ad networks
        "unity3d.com" to "Unity · Advertising",
        "ironsrc.com" to "ironSource · Advertising",
        "ironsource.com" to "ironSource · Advertising",
        "supersonicads.com" to "ironSource · Advertising",
        "applovin.com" to "AppLovin · Advertising",
        "vungle.com" to "Vungle · Advertising",
        "chartboost.com" to "Chartboost · Advertising",
        "adcolony.com" to "AdColony · Advertising",
        "inmobi.com" to "InMobi · Advertising",
        "mopub.com" to "MoPub · Advertising",
        "tapjoy.com" to "Tapjoy · Advertising",
        "startappservice.com" to "StartApp · Advertising",
        // Marketing / push
        "braze.com" to "Braze · Marketing",
        "appboy.com" to "Braze · Marketing",
        "onesignal.com" to "OneSignal · Push/Marketing",
        "urbanairship.com" to "Airship · Push/Marketing",
        "leanplum.com" to "Leanplum · Marketing",
        // Adobe / audience data
        "omtrdc.net" to "Adobe · Analytics",
        "demdex.net" to "Adobe · Audience data",
        "2o7.net" to "Adobe · Analytics",
        "bluekai.com" to "Oracle (BlueKai) · Audience data",
        "crwdcntrl.net" to "Lotame · Audience data",
        "rlcdn.com" to "LiveRamp · Audience data",
        "agkn.com" to "Neustar · Audience data",
        // Verification
        "moatads.com" to "Oracle (Moat) · Ad verification",
        "doubleverify.com" to "DoubleVerify · Ad verification",
        // Diagnostics
        "nr-data.net" to "New Relic · Diagnostics",
        "sentry.io" to "Sentry · Diagnostics",
        "bugsnag.com" to "Bugsnag · Diagnostics",
        // More ad networks / exchanges (coverage)
        "adform.net" to "Adform · Advertising",
        "serving-sys.com" to "Sizmek · Advertising",
        "smaato.net" to "Smaato · Advertising",
        "teads.tv" to "Teads · Advertising",
        "gumgum.com" to "GumGum · Advertising",
        "media.net" to "Media.net · Advertising",
        "revcontent.com" to "Revcontent · Advertising",
        "mgid.com" to "MGID · Advertising",
        "flashtalking.com" to "Flashtalking · Advertising",
        "everesttech.net" to "Adobe · Advertising",
        "adsafeprotected.com" to "Integral Ad Science · Ad verification",
        // Identity / audience data
        "id5-sync.com" to "ID5 · Identity graph",
        "tapad.com" to "Tapad · Audience data",
        "eyeota.net" to "Eyeota · Audience data",
        "krxd.net" to "Salesforce (Krux) · Audience data",
        "exelator.com" to "Nielsen (eXelate) · Audience data",
        // Analytics / session recording
        "heapanalytics.com" to "Heap · Analytics",
        "fullstory.com" to "FullStory · Session recording",
        "logrocket.com" to "LogRocket · Session recording",
        "smartlook.com" to "Smartlook · Session recording",
        "kissmetrics.com" to "Kissmetrics · Analytics",
        // Marketing / engagement
        "clevertap.com" to "CleverTap · Marketing",
        "moengage.com" to "MoEngage · Marketing",
        "instabug.com" to "Instabug · Diagnostics",
        // Microsoft / Yandex / TikTok / X
        "clarity.ms" to "Microsoft · Session analytics",
        "ads-twitter.com" to "X (Twitter) · Advertising",
        "mc.yandex.ru" to "Yandex · Analytics",
        "appmetrica.yandex.net" to "Yandex · Analytics",
        "tiktokv.com" to "TikTok · Analytics",
        "byteoversea.com" to "TikTok (ByteDance) · Analytics",
        // Xiaomi / MIUI (relevant on this device)
        "mistat.xiaomi.com" to "Xiaomi · Analytics",
        "tracking.miui.com" to "Xiaomi · Analytics",
        "sa.api.intl.miui.com" to "Xiaomi · Analytics",
        "ad.xiaomi.com" to "Xiaomi · Advertising",
        "miui.com" to "Xiaomi · Analytics",
        "xiaomi.com" to "Xiaomi · Analytics",
        "xmpush.xiaomi.com" to "Xiaomi · Push/Analytics",
        "googleapis.com" to "Google · Services"
    )

    /** "Company · Category" for a blocked host; falls back to the root domain. */
    fun label(host: String): String {
        val h = host.trim().lowercase().removeSuffix(".")
        var probe = h
        while (probe.contains('.')) {
            MAP[probe]?.let { return it }
            probe = probe.substring(probe.indexOf('.') + 1)
        }
        return "${rootDomain(h)} · Tracking"
    }

    /** Just the company/name part (before the "·"), for compact summaries. */
    fun companyOf(label: String): String = label.substringBefore(" · ").trim()

    private fun rootDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }
}
