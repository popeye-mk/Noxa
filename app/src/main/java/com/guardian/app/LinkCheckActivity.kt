package com.guardian.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** v1.13: the "Check link with Noxa" entry in the phone's Share menu. */
class LinkCheckActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            else -> ""
        }
        val link = LinkCheck.findLink(text)
        val parsed = link?.let { LinkCheck.hostOf(it) }
        if (link == null || parsed == null) {
            show("No link found", "Share a link (a web address) to Noxa to check it.", null, LinkCheck.Level.CAUTION)
            return
        }
        Thread {
            val (host, disguised) = parsed
            val r = try { check(host, disguised) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r == null) show("Couldn't check", "Something went wrong reading Noxa's lists.", null, LinkCheck.Level.CAUTION)
                else show(r.title, r.detail + "\n\nLink: " + link.take(200), link, r.level)
            }
        }.start()
    }

    private fun check(host: String, disguised: Boolean): LinkCheck.Result {
        AppStats.load(this)
        Stalkerware.load(this); Threats.load(this); ScamShield.load(this)
        val filter = BloomFilter.loadCurrent(this)
        val listed = filter.matchesHostOrParent(host)
        return LinkCheck.judge(
            host, disguised,
            userBlocked = AppStats.isUserBlocked(host),
            userAllowed = AppStats.isUserAllowed(host),
            stalkerware = Stalkerware.matches(host),
            dangerous = listed && Threats.matches(host),     // same two-list rule as live blocking
            fakeOf = FakeSites.lookalikeOf(host)?.name,
            tracker = listed,
            riskyEnding = ScamShield.riskyEndingAlways(host),
        )
    }

    private fun show(title: String, msg: String, link: String?, level: LinkCheck.Level) {
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(when (level) {
                LinkCheck.Level.OK -> "✓ $title"
                LinkCheck.Level.CAUTION -> "! $title"
                LinkCheck.Level.DANGER -> title
            })
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .setOnDismissListener { finish() }
        if (link != null && level != LinkCheck.Level.DANGER) {
            b.setNeutralButton(if (level == LinkCheck.Level.OK) "Open link" else "Open anyway") { _, _ ->
                val url = if (Regex("^(?i)https?://").containsMatchIn(link)) link else "https://$link"
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        b.show()
    }
}
