package com.guardian.app

import java.net.IDN
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.10 fake-site warning. Scam sites are often brand-new, so no list knows
 * them yet — but they have to LOOK like the brand they imitate. This catches
 * the three classic tricks, entirely on the phone (no list, no server):
 *
 *  A. look-alike letters:  paypa1.com, arnazon.com, g00gle.com, pаypal.com
 *     (Cyrillic "а"), pay-pal.com
 *  B. the real name hidden in front:  paypal.com.secure-check.top
 *  C. brand + scam word:  netflix-billing-update.com, login-paypal.site
 *
 * Built to avoid false alarms: the brand's own name under ANY ending
 * (paypal.de, apple.com.cn) is never flagged; names that are also everyday
 * words (apple, amazon, outlook...) only use rules A and B; short brands
 * (ING, DHL, eBay) are left out because too many real sites look like them.
 * A blocked site can always be allowed under "Allowed sites".
 */
object FakeSites {
    const val LABEL = "Dangerous site · Fake look-alike"

    class Brand(val label: String, val name: String, val generic: Boolean = false)

    private val BRANDS = listOf(
        // Payments, banks, crypto
        Brand("paypal", "PayPal"), Brand("revolut", "Revolut"), Brand("payoneer", "Payoneer"),
        Brand("skrill", "Skrill"), Brand("mastercard", "Mastercard"),
        Brand("americanexpress", "American Express"), Brand("westernunion", "Western Union"),
        Brand("moneygram", "MoneyGram"), Brand("wellsfargo", "Wells Fargo"),
        Brand("bankofamerica", "Bank of America"), Brand("citibank", "Citibank"),
        Brand("barclays", "Barclays"), Brand("santander", "Santander"),
        Brand("binance", "Binance"), Brand("coinbase", "Coinbase"), Brand("metamask", "MetaMask"),
        Brand("trustwallet", "Trust Wallet"), Brand("trezor", "Trezor"), Brand("bybit", "Bybit"),
        Brand("kucoin", "KuCoin"),
        // Netherlands
        Brand("rabobank", "Rabobank"), Brand("abnamro", "ABN AMRO"), Brand("snsbank", "SNS Bank"),
        Brand("triodos", "Triodos"), Brand("postnl", "PostNL"),
        Brand("belastingdienst", "Belastingdienst"), Brand("digid", "DigiD"),
        Brand("marktplaats", "Marktplaats"), Brand("ziggo", "Ziggo"),
        // North Macedonia
        Brand("halkbank", "Halkbank"), Brand("stopanska", "Stopanska banka"),
        Brand("komercijalna", "Komercijalna banka"), Brand("telekom", "Telekom", generic = true),
        // Accounts people get phished for
        Brand("apple", "Apple", generic = true), Brand("icloud", "iCloud"),
        Brand("microsoft", "Microsoft"), Brand("outlook", "Outlook", generic = true),
        Brand("office365", "Microsoft 365"), Brand("hotmail", "Hotmail"),
        Brand("google", "Google"), Brand("gmail", "Gmail"), Brand("youtube", "YouTube"),
        Brand("facebook", "Facebook"), Brand("instagram", "Instagram"), Brand("whatsapp", "WhatsApp"),
        Brand("netflix", "Netflix"), Brand("disneyplus", "Disney+"), Brand("primevideo", "Prime Video"),
        Brand("spotify", "Spotify"), Brand("linkedin", "LinkedIn"), Brand("twitter", "Twitter / X"),
        Brand("telegram", "Telegram", generic = true), Brand("tiktok", "TikTok"),
        Brand("snapchat", "Snapchat"), Brand("discord", "Discord", generic = true),
        Brand("steamcommunity", "Steam"), Brand("steampowered", "Steam"), Brand("roblox", "Roblox"),
        Brand("playstation", "PlayStation"), Brand("nintendo", "Nintendo"),
        Brand("epicgames", "Epic Games"),
        // Shops, parcels, files
        Brand("amazon", "Amazon", generic = true), Brand("aliexpress", "AliExpress"),
        Brand("zalando", "Zalando"), Brand("vinted", "Vinted"), Brand("walmart", "Walmart"),
        Brand("fedex", "FedEx"), Brand("dropbox", "Dropbox"), Brand("docusign", "DocuSign"),
        Brand("wetransfer", "WeTransfer"), Brand("adobe", "Adobe", generic = true),
        Brand("yahoo", "Yahoo"), Brand("samsung", "Samsung"), Brand("huawei", "Huawei"),
        Brand("xiaomi", "Xiaomi"),
    )

    /** Words scammers glue to a brand name (English, Dutch, Macedonian). */
    private val SCAM_WORDS = setOf(
        "login", "signin", "logon", "verify", "verification", "secure", "security",
        "account", "accounts", "update", "billing", "support", "unlock", "confirm",
        "wallet", "refund", "payment", "auth", "recovery", "suspended", "alert", "helpdesk",
        "inloggen", "verificatie", "beveiliging", "betaling", "bevestigen", "controle",
        "klantenservice", "terugbetaling",
        "najava", "potvrda", "plakjanje", "smetka",
    )

    /** Endings a scammer puts AFTER the real name to fake a full address. */
    private val TLD_LIKE = setOf("com", "net", "org", "co", "nl", "mk", "de", "uk", "eu",
        "io", "info", "app", "us", "fr", "be")

    /** ".co.uk"-style endings: <2-letter country> preceded by one of these. */
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    /** Free hosting where every customer gets their own name: the name before
     *  the ending is the "site", e.g. paypa1-login.netlify.app. */
    private val PLATFORM_SUFFIXES = setOf(
        "github.io", "blogspot.com", "pages.dev", "web.app", "firebaseapp.com", "netlify.app",
        "vercel.app", "workers.dev", "herokuapp.com", "azurewebsites.net", "appspot.com",
        "wixsite.com", "weebly.com", "000webhostapp.com", "glitch.me", "r2.dev",
        "sharepoint.com", "myshopify.com", "wordpress.com", "webflow.io", "framer.app",
        "godaddysites.com", "square.site", "onrender.com", "ngrok.io", "ngrok-free.app",
        "replit.app",
    )

    /** CDNs name their servers after the customer ("www.paypal.com.edgekey.net"
     *  is PayPal's real Akamai server) — never treat those as fakes. */
    private val CDN_SITES = setOf(
        "edgekey.net", "edgesuite.net", "akamaiedge.net", "akamaized.net", "akamai.net",
        "akamaihd.net", "cloudfront.net", "fastly.net", "fastlylb.net", "cloudflare.net",
        "trafficmanager.net", "azureedge.net", "azurefd.net", "edgecastcdn.net", "llnwd.net",
        "cdn77.org", "2o7.net", "omtrdc.net", "sc.omtrdc.net", "chinacache.net", "wscdns.com",
        "kunlunca.com", "alikunlun.com", "cdngslb.com", "akadns.net", "akamaitechnologies.com",
        "nsatc.net",   // Microsoft's own server network (*.microsoft.com.nsatc.net)
    )

    /** Real names that happen to look like a brand (i reads as l). */
    private val KNOWN_REAL = setOf(
        "appie",     // Albert Heijn's app (NL), not Apple
        "gmall", "lcloud",
        "wal-mart",  // Walmart's own old domain (found scanning the top 100k sites)
        "1cloud",    // 1cloud.ru, a real hosting company — not iCloud
    )

    // Letters from other alphabets that look like Latin ones.
    private val HOMOGLYPHS = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x',
        'і' to 'i', 'ј' to 'j', 'ѕ' to 's', 'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w', 'һ' to 'h',
        'ӏ' to 'l', 'к' to 'k', 'ɡ' to 'g', 'ı' to 'i', 'ɩ' to 'i', 'ʏ' to 'y', 'ⅼ' to 'l',
        'ο' to 'o', 'α' to 'a', 'ν' to 'v', 'ρ' to 'p', 'ι' to 'i', 'κ' to 'k', 'υ' to 'u',
        'χ' to 'x', 'ε' to 'e',
    )
    // Characters people read as the same letter. i/l/1 all become 'l'.
    private val LOOKALIKE = mapOf(
        '0' to 'o', '1' to 'l', 'i' to 'l', '3' to 'e', '4' to 'a', '5' to 's',
        '7' to 't', '9' to 'g',
    )

    // Declared AFTER the letter maps: object properties initialise in order,
    // and skeleton() needs those maps.
    private val byLabel: Map<String, Brand> = BRANDS.associateBy { it.label }
    private val bySkeleton: Map<String, Brand> = BRANDS.associateBy { skeleton(it.label) }

    /** "Paypa1", "pаypal" (Cyrillic а), "pay-pal" -> the same skeleton as "paypal". */
    fun skeleton(label: String): String {
        val nfd = Normalizer.normalize(label.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (c in nfd) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue   // é -> e
            if (c == '-') continue
            val latin = HOMOGLYPHS[c] ?: c
            sb.append(LOOKALIKE[latin] ?: latin)
        }
        return sb.toString().replace("rn", "m").replace("vv", "w")
    }

    private val NONE = Brand("", "")
    private val cache = ConcurrentHashMap<String, Brand>()

    /** The brand [host] pretends to be, or null if it doesn't look fake. Fast
     *  enough for every lookup (results are cached). */
    fun lookalikeOf(host: String): Brand? {
        val h = host.trim().lowercase().removeSuffix(".")
        cache[h]?.let { return if (it === NONE) null else it }
        val r = try { compute(h) } catch (_: Exception) { null }
        if (cache.size > 4000) cache.clear()
        cache[h] = r ?: NONE
        return r
    }

    private fun compute(h: String): Brand? {
        val labels = h.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        val ri = registrableIndex(labels)
        if (ri < 0) return null
        val reg = labels[ri]
        if (reg in byLabel || reg in KNOWN_REAL) return null   // the real name, under any ending
        if (labels.subList(ri, labels.size).joinToString(".") in CDN_SITES) return null

        // A: the site's own name is a look-alike of a brand.
        bySkeleton[skeleton(unicode(reg))]?.let { return it }

        // B: "paypal.com.<something else>" — the real address used as decoration.
        for (i in 0..ri - 2) {
            val b = brandOf(unicode(labels[i])) ?: continue
            if (labels[i + 1] in TLD_LIKE) return b
        }

        // C: brand + scam word in one name: "netflix-billing-update", "login-paypal".
        // Brands that are also everyday words need TWO scam words
        // ("apple-support-update" yes, "economic-outlook-update" no).
        for (i in 0..ri) {
            val lab = unicode(labels[i])
            if ('-' !in lab) continue
            val tokens = lab.split('-').filter { it.isNotEmpty() }
            val brand = tokens.firstNotNullOfOrNull { brandOf(it) } ?: continue
            val scamWords = tokens.count { it in SCAM_WORDS }
            if (scamWords >= (if (brand.generic) 2 else 1)) return brand
        }
        return null
    }

    private fun brandOf(label: String): Brand? = byLabel[label] ?: bySkeleton[skeleton(label)]

    /** Index of the "site name" label (just left of the ending), or -1. */
    fun registrableIndex(labels: List<String>): Int {
        val n = labels.size
        if (n < 2) return -1
        if (n >= 3 && "${labels[n - 2]}.${labels[n - 1]}" in PLATFORM_SUFFIXES) return n - 3
        if (n >= 3 && labels[n - 1].length == 2 && labels[n - 2] in SECOND_LEVEL) return n - 3
        return n - 2
    }

    /** "xn--pypal-4ve" -> "pаypal" (so mixed alphabets can be compared). */
    private fun unicode(label: String): String =
        if (!label.startsWith("xn--")) label
        else try { IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { label }
}
