package com.guardian.app

/**
 * Phase 3 — the plain-language layer. Pure template rules over the aggregates in
 * AppStats: no jargon, no DNS/SNI/company-domain strings leaking into the UI, no
 * model. Turns raw block counts into sentences a non-technical person reads once
 * and understands.
 */
object Explanations {

    /** Top-of-dashboard summary across all apps. */
    fun header(totalBlocked: Long): String {
        val g = AppStats.globalCompanyCounts()
        if (totalBlocked <= 0L || g.isEmpty())
            return "Nothing blocked yet — turn Guardian on and use the phone for a bit."
        val companies = g.keys.map { Trackers.companyOf(it) }.toSet().size
        val top = g.entries.sortedByDescending { it.value }
            .map { Trackers.companyOf(it.key) }
            .distinct().take(3)
            .joinToString(", ")
        return "In the last 30 days Guardian blocked $totalBlocked tracking attempts from " +
            "$companies companies. Most persistent: $top."
    }

    /** One-line who/why for an app row. */
    fun appSummary(pkg: String): String {
        val counts = AppStats.companyCounts(pkg)
        if (counts.isEmpty()) return "No trackers blocked — looks clean so far."
        val top = counts.maxByOrNull { it.value }!!.key
        val companies = counts.keys.map { Trackers.companyOf(it) }.toSet().size
        return if (companies <= 1) "Mostly $top"
        else "Reached $companies tracking companies — mostly $top"
    }

    /** Full breakdown shown when a user taps an app. */
    fun appDetail(appLabel: String, pkg: String): String {
        val counts = AppStats.companyCounts(pkg).entries.sortedByDescending { it.value }
        if (counts.isEmpty()) return "Guardian hasn't blocked any trackers from $appLabel yet."
        val total = counts.sumOf { it.value }
        val sb = StringBuilder()
        sb.append("$appLabel made $total tracking attempts that Guardian blocked.\n\n")
        sb.append("Who it was trying to reach:\n")
        for (e in counts) sb.append("  • ${e.key} — ${e.value}\n")
        return sb.toString().trimEnd()
    }
}
