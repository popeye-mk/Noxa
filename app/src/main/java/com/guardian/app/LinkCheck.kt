package com.guardian.app

/**
 * v1.13 "Check a link": share any link to Noxa (long-press → Share → Noxa)
 * and get an answer BEFORE opening it. Offline — uses the same lists and
 * fake-site detection that protect your lookups; the link never leaves the
 * phone.
 */
object LinkCheck {

    enum class Level { OK, CAUTION, DANGER }

    class Result(val host: String, val level: Level, val title: String, val detail: String)

    private val URL_RE = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
    private val BARE_RE = Regex("""(?i)\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z][a-z0-9-]{1,62}(?:/[^\s<>"']*)?""")

    val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly",
        "rb.gy", "shorturl.at", "tiny.cc", "rebrand.ly", "s.id", "t.ly", "lnkd.in", "v.gd",
        "shorturl.gg", "qrco.de", "u.to", "clck.ru")

    /** Pure, unit-tested: the first link in a shared text, or null. */
    fun findLink(text: String): String? =
        URL_RE.find(text)?.value ?: BARE_RE.find(text)?.value

    /** Pure, unit-tested: the REAL host of a link. "https://paypal.com@evil.xyz/x"
     *  goes to evil.xyz — the part before @ is a disguise. Returns (host, disguised). */
    fun hostOf(link: String): Pair<String, Boolean>? {
        var s = link.trim().trimEnd('.', ',', ')', ']', '!', '?', ';')
        s = s.replace(Regex("^(?i)[a-z][a-z0-9+.-]*://"), "")
        val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
        val disguised = authority.contains('@')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase().removeSuffix(".")
        if (!host.contains('.') || host.length > 253) return null
        return host to disguised
    }

    fun isIpLiteral(host: String): Boolean = DnsProviders.isIpv4(host) || host.startsWith("[")

    /** Pure decision, unit-tested; the lookups are passed in. */
    fun judge(
        host: String, disguised: Boolean,
        userBlocked: Boolean, userAllowed: Boolean,
        stalkerware: Boolean, dangerous: Boolean, fakeOf: String?,
        tracker: Boolean, riskyEnding: String?,
    ): Result = when {
        userBlocked -> Result(host, Level.DANGER, "On your block list",
            "You blocked $host yourself. Noxa won't let it load.")
        stalkerware -> Result(host, Level.DANGER, "⚠ Spyware site — don't open",
            "$host belongs to stalkerware (apps that secretly spy on phones). Don't open it or install anything from it.")
        fakeOf != null -> Result(host, Level.DANGER, "⚠ Fake $fakeOf site — don't open",
            "$host pretends to be $fakeOf, but it is not $fakeOf's real website. Never enter a password or card details there.")
        dangerous -> Result(host, Level.DANGER, "⚠ Dangerous site — don't open",
            "$host is on Noxa's scam / malware list.")
        disguised -> Result(host, Level.DANGER, "⚠ Disguised link",
            "This link only LOOKS like it goes somewhere else — it really opens $host. That trick is used by scams.")
        userAllowed -> Result(host, Level.OK, "On your allowed list",
            "You allowed $host yourself.")
        isIpLiteral(host) -> Result(host, Level.CAUTION, "Link to a bare number address",
            "It opens $host directly instead of a website name. Real banks, shops and couriers don't send links like this.")
        host in SHORTENERS -> Result(host, Level.CAUTION, "Shortened link",
            "$host hides where the link really goes. If you don't know who sent it, don't open it.")
        riskyEnding != null -> Result(host, Level.CAUTION, "Risky ending .$riskyEnding",
            "Most sites ending in .$riskyEnding are scams. Only open it if you trust who sent it.")
        tracker -> Result(host, Level.CAUTION, "Tracker / ad link",
            "$host is a tracking or advertising address. Not dangerous, but Noxa blocks it — it may not open.")
        else -> Result(host, Level.OK, "No known problems",
            "$host isn't on any scam, malware or tracker list and doesn't imitate a known brand. " +
            "Still check it's the site you expect before logging in.")
    }
}
