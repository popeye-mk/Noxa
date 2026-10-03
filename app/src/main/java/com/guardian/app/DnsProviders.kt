package com.guardian.app

import android.content.Context
import java.net.InetAddress

/**
 * v1.8: the user's choice of upstream resolver — who answers the lookups
 * Noxa ALLOWS. Default stays Cloudflare (a mainstream resolver keeps users
 * in a large anonymity set); the others are well-known privacy resolvers,
 * plus "your own" for people who run one.
 *
 * Each provider has a plain-DNS IP (always works) and a DNS-over-HTTPS URL
 * (preferred when "Encrypted DNS" is on). Noxa's own traffic bypasses the
 * VPN, so a DoH hostname resolves through the system resolver — that one
 * lookup reveals only which resolver you use, never what you look up.
 */
object DnsProviders {

    class Provider(val id: String, val name: String, val blurb: String, val ip: String, val doh: String?) {
        val address: InetAddress get() = InetAddress.getByName(ip)
    }

    val BUILT_IN: List<Provider> = listOf(
        Provider("cloudflare", "Cloudflare", "Fast, mainstream, no logs policy (default)",
            "1.1.1.1", "https://1.1.1.1/dns-query"),
        Provider("quad9", "Quad9", "Non-profit (Switzerland), also blocks malware domains",
            "9.9.9.9", "https://dns.quad9.net/dns-query"),
        Provider("mullvad", "Mullvad", "No-logging VPN provider's public resolver (Sweden)",
            "194.242.2.2", "https://dns.mullvad.net/dns-query"),
        Provider("adguard", "AdGuard DNS", "Also blocks ads at the resolver (extra layer)",
            "94.140.14.14", "https://dns.adguard-dns.com/dns-query"),
        Provider("google", "Google", "Fast and everywhere; Google sees your lookups",
            "8.8.8.8", "https://8.8.8.8/dns-query"),
    )

    const val CUSTOM_ID = "custom"
    private const val PREFS = "guardian_dns"
    private const val KEY_ID = "provider"
    private const val KEY_CUSTOM_IP = "custom_ip"
    private const val KEY_CUSTOM_DOH = "custom_doh"

    /** The user's choice. */
    @Volatile var current: Provider = BUILT_IN[0]
        private set

    // v1.11 automatic fallback: if the chosen resolver stops answering, use
    // the next one for 10 minutes, then try the user's choice again. Never
    // changes the saved choice.
    @Volatile private var fallback: Provider? = null
    @Volatile private var fallbackUntil = 0L
    @Volatile private var lastFailover = 0L
    private const val FALLBACK_MS = 10L * 60 * 1000

    /** What the service actually uses right now (the choice, or a temporary fallback). */
    val active: Provider
        get() {
            val f = fallback
            return if (f != null && System.currentTimeMillis() < fallbackUntil) f else current
        }

    /** Non-null while a fallback is in use: (fallback, the provider that failed). */
    fun fallbackInfo(now: Long = System.currentTimeMillis()): Pair<Provider, Provider>? {
        val f = fallback ?: return null
        return if (now < fallbackUntil) f to current else null
    }

    /** Called by the service after several lookups in a row got no answer.
     *  Returns the provider switched to, or null if it's too soon to switch again. */
    fun failover(now: Long = System.currentTimeMillis()): Provider? {
        if (now - lastFailover < 2L * 60 * 1000) return null     // at most every 2 min
        val failing = active
        val next = nextAfter(failing.id)
        lastFailover = now
        fallback = if (next.id == current.id) null else next
        fallbackUntil = now + FALLBACK_MS
        return next
    }

    /** Pure, unit-tested: the next built-in after [id] (a custom server falls back to the first). */
    fun nextAfter(id: String): Provider {
        val i = BUILT_IN.indexOfFirst { it.id == id }
        return BUILT_IN[(i + 1) % BUILT_IN.size]
    }

    fun clearFallback() { fallback = null; fallbackUntil = 0L }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = when (val id = p.getString(KEY_ID, BUILT_IN[0].id)) {
            CUSTOM_ID -> custom(p.getString(KEY_CUSTOM_IP, "") ?: "", p.getString(KEY_CUSTOM_DOH, "") ?: "")
                ?: BUILT_IN[0]
            else -> BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN[0]
        }
    }

    fun select(ctx: Context, id: String) {
        val prov = BUILT_IN.firstOrNull { it.id == id } ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ID, id).apply()
        current = prov
        clearFallback()
    }

    /** Returns null (and changes nothing) if [ip] isn't a valid IPv4 address or
     *  [dohUrl] isn't https. */
    fun selectCustom(ctx: Context, ip: String, dohUrl: String): Provider? {
        val prov = custom(ip, dohUrl) ?: return null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, CUSTOM_ID).putString(KEY_CUSTOM_IP, prov.ip)
            .putString(KEY_CUSTOM_DOH, prov.doh ?: "").apply()
        current = prov
        clearFallback()
        return prov
    }

    fun custom(ip: String, dohUrl: String): Provider? {
        val i = ip.trim()
        if (!isIpv4(i)) return null
        val d = dohUrl.trim()
        val doh = if (d.isEmpty()) null else if (d.startsWith("https://") && d.length > 12 && !d.contains(' ')) d else return null
        return Provider(CUSTOM_ID, "Your own ($i)", "Custom resolver", i, doh)
    }

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 && (p.length == 1 || p[0] != '0') }
    }

    /** For backups: the id, or "custom:ip:doh". */
    fun exportValue(): String =
        if (current.id == CUSTOM_ID) "$CUSTOM_ID|${current.ip}|${current.doh ?: ""}" else current.id

    fun importValue(ctx: Context, v: String) {
        if (v.startsWith("$CUSTOM_ID|")) {
            val parts = v.split('|', limit = 3)
            selectCustom(ctx, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        } else select(ctx, v)
    }
}
