package com.privacyguard.core

/** Lista e brokereve te te dhenave, rrjeteve te reklamave dhe SDK-ve te gjurmimit. */
object Trackers {
    const val BROKER = "Broker të dhënash / vendndodhje"
    const val ADS = "Rrjet reklamash"
    const val ATTR = "Atribuim / gjurmim instalimesh"
    const val ANALYTICS = "Analitikë / profilizim"
    const val META = "Meta (Facebook, Instagram, WhatsApp)"
    const val GOOGLE = "Google reklama / analitikë"
    const val SAMSUNG = "Samsung reklama / analitikë"
    const val BYTEDANCE = "ByteDance / TikTok"

    data class T(val company: String, val category: String)

    val weight = mapOf(BROKER to 3, ATTR to 2, ADS to 2, ANALYTICS to 2, BYTEDANCE to 2, META to 1, GOOGLE to 1, SAMSUNG to 1)

    /** Kur kategoria i perket vete pronarit te app-it, nuk eshte "tracker i trete". */
    private val firstParty = mapOf(
        META to listOf("com.facebook.", "com.instagram.", "com.whatsapp"),
        GOOGLE to listOf("com.google.", "com.android."),
        SAMSUNG to listOf("com.samsung.", "com.sec."),
        BYTEDANCE to listOf("com.zhiliaoapp.", "com.ss.android."),
    )

    private val list = mapOf(
        "x-mode.io" to T("X-Mode / Outlogic", BROKER),
        "xmode.io" to T("X-Mode / Outlogic", BROKER),
        "outlogic.io" to T("X-Mode / Outlogic", BROKER),
        "cuebiq.com" to T("Cuebiq", BROKER),
        "huq.io" to T("Huq Industries", BROKER),
        "foursquare.com" to T("Foursquare (Pilgrim SDK)", BROKER),
        "placed.com" to T("Placed / Foursquare", BROKER),
        "safegraph.com" to T("SafeGraph", BROKER),
        "unacast.com" to T("Unacast / Gravy", BROKER),
        "gravyanalytics.com" to T("Gravy Analytics", BROKER),
        "venntel.com" to T("Venntel", BROKER),
        "tutela.com" to T("Tutela", BROKER),
        "predicio.fr" to T("Predicio", BROKER),
        "fysical.com" to T("Fysical", BROKER),
        "inloco.com.br" to T("Incognia / In Loco", BROKER),
        "incognia.com" to T("Incognia", BROKER),
        "radar.io" to T("Radar", BROKER),
        "areametrics.com" to T("AreaMetrics", BROKER),
        "blis.com" to T("Blis", BROKER),
        "near.co" to T("Near Intelligence", BROKER),
        "mobilewalla.com" to T("Mobilewalla", BROKER),
        "liveramp.com" to T("LiveRamp", BROKER),
        "rlcdn.com" to T("LiveRamp", BROKER),
        "acxiom.com" to T("Acxiom", BROKER),
        "bluekai.com" to T("Oracle BlueKai", BROKER),
        "krxd.net" to T("Salesforce Krux", BROKER),
        "lotame.com" to T("Lotame", BROKER),
        "crwdcntrl.net" to T("Lotame", BROKER),
        "exelator.com" to T("Nielsen eXelate", BROKER),
        "tapad.com" to T("Tapad (Experian)", BROKER),
        "adsquare.com" to T("adsquare", BROKER),
        "tamoco.com" to T("Tamoco", BROKER),
        "kochava.com" to T("Kochava", BROKER),
        "kochava.io" to T("Kochava", BROKER),
        "appsflyer.com" to T("AppsFlyer", ATTR),
        "appsflyersdk.com" to T("AppsFlyer", ATTR),
        "adjust.com" to T("Adjust", ATTR),
        "adjust.io" to T("Adjust", ATTR),
        "adj.st" to T("Adjust", ATTR),
        "branch.io" to T("Branch", ATTR),
        "app.link" to T("Branch", ATTR),
        "singular.net" to T("Singular", ATTR),
        "sng.link" to T("Singular", ATTR),
        "tenjin.io" to T("Tenjin", ATTR),
        "applovin.com" to T("AppLovin", ADS),
        "applvn.com" to T("AppLovin", ADS),
        "unityads.unity3d.com" to T("Unity Ads", ADS),
        "ironsrc.mobi" to T("ironSource", ADS),
        "ironsrc.com" to T("ironSource", ADS),
        "supersonicads.com" to T("ironSource", ADS),
        "inmobi.com" to T("InMobi", ADS),
        "mintegral.com" to T("Mintegral", ADS),
        "rayjump.com" to T("Mintegral", ADS),
        "vungle.com" to T("Vungle / Liftoff", ADS),
        "liftoff.io" to T("Liftoff", ADS),
        "chartboost.com" to T("Chartboost", ADS),
        "criteo.com" to T("Criteo", ADS),
        "criteo.net" to T("Criteo", ADS),
        "taboola.com" to T("Taboola", ADS),
        "outbrain.com" to T("Outbrain", ADS),
        "smaato.net" to T("Smaato", ADS),
        "pubmatic.com" to T("PubMatic", ADS),
        "rubiconproject.com" to T("Magnite", ADS),
        "adnxs.com" to T("Xandr", ADS),
        "moatads.com" to T("Oracle Moat", ADS),
        "yandex.ru" to T("Yandex", ADS),
        "appmetrica.yandex.com" to T("Yandex AppMetrica", ANALYTICS),
        "amplitude.com" to T("Amplitude", ANALYTICS),
        "mixpanel.com" to T("Mixpanel", ANALYTICS),
        "segment.io" to T("Segment", ANALYTICS),
        "segment.com" to T("Segment", ANALYTICS),
        "braze.com" to T("Braze", ANALYTICS),
        "appboy.com" to T("Braze", ANALYTICS),
        "onesignal.com" to T("OneSignal", ANALYTICS),
        "flurry.com" to T("Flurry", ANALYTICS),
        "comscore.com" to T("comScore", ANALYTICS),
        "scorecardresearch.com" to T("comScore", ANALYTICS),
        "newrelic.com" to T("New Relic", ANALYTICS),
        "clevertap.com" to T("CleverTap", ANALYTICS),
        "wzrkt.com" to T("CleverTap", ANALYTICS),
        "moengage.com" to T("MoEngage", ANALYTICS),
        "uxcam.com" to T("UXCam (regjistron ekranin)", ANALYTICS),
        "smartlook.com" to T("Smartlook (regjistron ekranin)", ANALYTICS),
        "hotjar.com" to T("Hotjar", ANALYTICS),
        "graph.facebook.com" to T("Meta Graph API", META),
        "facebook.com" to T("Meta", META),
        "facebook.net" to T("Meta", META),
        "fbcdn.net" to T("Meta CDN", META),
        "instagram.com" to T("Meta / Instagram", META),
        "cdninstagram.com" to T("Meta / Instagram", META),
        "whatsapp.net" to T("Meta / WhatsApp", META),
        "whatsapp.com" to T("Meta / WhatsApp", META),
        "doubleclick.net" to T("Google DoubleClick", GOOGLE),
        "googleadservices.com" to T("Google Ads", GOOGLE),
        "googlesyndication.com" to T("Google AdSense", GOOGLE),
        "app-measurement.com" to T("Google Analytics for Firebase", GOOGLE),
        "google-analytics.com" to T("Google Analytics", GOOGLE),
        "admob.com" to T("Google AdMob", GOOGLE),
        "crashlytics.com" to T("Firebase Crashlytics", GOOGLE),
        "samsungads.com" to T("Samsung Ads", SAMSUNG),
        "samsungacr.com" to T("Samsung ACR", SAMSUNG),
        "samsungrm.net" to T("Samsung", SAMSUNG),
        "pangle.io" to T("Pangle (ByteDance)", BYTEDANCE),
        "pangleglobal.com" to T("Pangle (ByteDance)", BYTEDANCE),
        "byteoversea.com" to T("ByteDance", BYTEDANCE),
        "tiktokv.com" to T("TikTok", BYTEDANCE),
        "tiktok.com" to T("TikTok", BYTEDANCE),
    )

    fun classify(host: String): T? {
        val parts = host.lowercase().trimEnd('.').split('.')
        for (i in 0 until parts.size - 1) {
            list[parts.subList(i, parts.size).joinToString(".")]?.let { return it }
        }
        return null
    }

    fun isFirstParty(t: T, pkg: String) = firstParty[t.category]?.any { pkg.startsWith(it) } == true

    /**
     * Domene qe sjellin PERMBAJTJE (foto, video, mesazhe). Shenohen ne raport, por nuk bllokohen
     * kurre: bllokimi i tyre do te prishte Instagram-in/WhatsApp-in brenda app-eve te tjera.
     */
    private val content = setOf(
        "facebook.com", "facebook.net", "fbcdn.net", "instagram.com", "cdninstagram.com",
        "whatsapp.net", "whatsapp.com", "tiktok.com", "tiktokv.com", "byteoversea.com", "samsungrm.net",
    )

    /** Pika gjurmimi brenda atyre domeneve: SDK-te, pixel-at, reklamat. Keto bllokohen. */
    private val trackingEndpoints = setOf(
        "graph.facebook.com", "an.facebook.com", "connect.facebook.net", "pixel.facebook.com", "analytics.tiktok.com",
    )

    private fun suffixes(host: String): List<String> {
        val p = host.lowercase().trimEnd('.').split('.')
        return (0 until p.size - 1).map { p.subList(it, p.size).joinToString(".") }
    }

    /**
     * A mund te bllokohet kjo kerkese pa prishur app-in? Jo kurre per vete pronarin
     * (Instagram -> instagram.com) dhe jo per domenet e permbajtjes.
     */
    fun blockable(host: String, pkg: String): Boolean {
        val t = classify(host) ?: return false
        if (isFirstParty(t, pkg)) return false
        val s = suffixes(host)
        if (s.any { it in trackingEndpoints }) return true
        return s.none { it in content }
    }
}
