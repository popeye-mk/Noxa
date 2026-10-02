package com.guardian.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.IDN

/** v1.10: fake-site warning, strict mode endings, pop-up ad app scoring. */
class ScamProtectionTest {

    // --- Fake look-alike sites -------------------------------------------

    private fun fake(host: String) = FakeSites.lookalikeOf(host)?.name

    @Test fun catchesLookAlikeLetters() {
        assertEquals("PayPal", fake("paypa1.com"))
        assertEquals("PayPal", fake("paypai.com"))          // i for l
        assertEquals("PayPal", fake("pay-pal.com"))
        assertEquals("Amazon", fake("arnazon.com"))         // rn for m
        assertEquals("Google", fake("g00gle.com"))
        assertEquals("Facebook", fake("faceb00k.pl"))
        assertEquals("ABN AMRO", fake("abnarnro.nl"))
        assertEquals("Steam", fake("steamcommunlty.com"))
    }

    @Test fun catchesOtherAlphabets() {
        // "pаypal.com" with a Cyrillic а — looks identical on screen.
        val puny = IDN.toASCII("pаypal.com")
        assertTrue(puny.startsWith("xn--"))
        assertEquals("PayPal", fake(puny))
    }

    @Test fun catchesRealNameUsedAsDecoration() {
        assertEquals("PayPal", fake("paypal.com.secure-check.top"))
        assertEquals("PayPal", fake("www.paypal.com.account-update.xyz"))
    }

    @Test fun catchesBrandPlusScamWord() {
        assertEquals("Netflix", fake("netflix-billing-update.com"))
        assertEquals("PayPal", fake("login-paypal.site"))
        assertEquals("PayPal", fake("paypa1-login.netlify.app"))
        assertEquals("Rabobank", fake("rabobank-verificatie.nl"))
        assertEquals("Halkbank", fake("halkbank-najava.com"))
        assertEquals("Apple", fake("apple-support-update.com"))   // everyday word: needs 2
    }

    @Test fun neverFlagsTheRealSites() {
        val real = listOf(
            "paypal.com", "www.paypal.com", "paypal.de", "paypalobjects.com", "paypal-community.com",
            "apple.com", "apple.com.cn", "icloud.com", "google.co.uk", "accounts.google.com",
            "www.netflix.com", "nflxvideo.net", "amazon.nl", "amazonaws.com",
            "s3.eu-central-1.amazonaws.com", "media-amazon.com", "login.microsoftonline.com",
            "outlook.office365.com", "login.live.com", "steamcommunity.com", "i.instagram.com",
            "web.whatsapp.com", "telekom.mk", "mojtelekom.mk", "komercijalna.mk", "stopanska.mk",
            "halkbank.mk", "ziggo.nl", "mijn.ziggo.nl", "digid.nl", "jouw.postnl.nl", "ing.nl",
            "youtube.com", "googlevideo.com", "r3---sn-abc.googlevideo.com", "linkedin.com",
            "discordapp.com", "roblox.com", "rbxcdn.com", "github.com", "example.com",
            "yahoo.co.jp", "adobelogin.com", "telegram.org",
            "appie.nl", "www.appie.nl", "paypal.com.akadns.net",
        )
        for (d in real) assertNull("$d must not be flagged", fake(d))
    }

    @Test fun everydayWordsAndCdnsAreNotFake() {
        assertNull(fake("economic-outlook-update.com"))     // 'outlook' the word, 1 scam word
        assertNull(fake("apple.stackexchange.com"))
        assertNull(fake("appleinsider.com"))
        assertNull(fake("www.paypal.com.edgekey.net"))      // PayPal's real Akamai server
    }

    @Test fun skeletonTreatsLookAlikesAsEqual() {
        assertEquals(FakeSites.skeleton("paypal"), FakeSites.skeleton("PAYPA1"))
        assertEquals(FakeSites.skeleton("amazon"), FakeSites.skeleton("arnazon"))
        assertEquals(FakeSites.skeleton("netflix"), FakeSites.skeleton("netfl1x"))
    }

    @Test fun findsTheSiteNameLabel() {
        assertEquals(1, FakeSites.registrableIndex("www.bbc.co.uk".split('.')))
        assertEquals(0, FakeSites.registrableIndex("paypal.com".split('.')))
        assertEquals(1, FakeSites.registrableIndex("x.mysite.github.io".split('.')))
    }

    // --- Strict mode: risky web endings ----------------------------------

    private val endings = ScamShield.parse("# test\ncam\nloan\nus.com\n.bond\nBAD LINE\n\n")

    @Test fun parsesEndings() {
        assertEquals(setOf("cam", "loan", "us.com", "bond"), endings)
    }

    @Test fun matchesOnlyTheEnding() {
        assertEquals("cam", ScamShield.endingIn(endings, "shop.example.cam"))
        assertEquals("us.com", ScamShield.endingIn(endings, "evil.us.com"))
        assertEquals("bond", ScamShield.endingIn(endings, "win-big.bond."))
        assertNull(ScamShield.endingIn(endings, "camera.com"))
        assertNull(ScamShield.endingIn(endings, "example.com"))
        assertNull(ScamShield.endingIn(endings, "cam"))
        assertNull(ScamShield.endingIn(emptySet(), "x.cam"))
    }

    @Test fun bundledEndingsNeverIncludeEverydayOnes() {
        val f = java.io.File("src/main/assets/risky-tlds.txt")
        val set = ScamShield.parse(f.readText())
        assertTrue("bundled list looks empty", set.size >= 20)
        for (t in listOf("com", "net", "org", "nl", "mk", "de", "eu", "io", "app", "co.uk"))
            assertTrue(".$t must never be in the strict list", t !in set)
    }

    // --- Pop-up ad app finder ----------------------------------------------

    private fun sig(draws: Boolean = false, hidden: Boolean = false, a11y: Boolean = false,
                    ads: Long = 0, side: Boolean = false, days: Int = 400) =
        AdwareScan.Signals(draws, hidden, a11y, ads, side, days)

    @Test fun typicalAdwareScoresHigh() {
        val (n, reasons) = AdwareScan.score(sig(draws = true, hidden = true, ads = 500, days = 3))
        assertTrue(n >= AdwareScan.THRESHOLD)
        assertEquals(4, reasons.size)
    }

    @Test fun ordinaryAppIsNotSuspect() {
        assertTrue(AdwareScan.score(sig()).first < AdwareScan.THRESHOLD)
        assertTrue(AdwareScan.score(sig(ads = 120)).first < AdwareScan.THRESHOLD)   // ad-funded game
        assertTrue(AdwareScan.score(sig(draws = true)).first < AdwareScan.THRESHOLD) // chat bubbles
    }

    @Test fun countsOnlyAdLookups() {
        val counts = mapOf("Google · Advertising" to 30L, "AppLovin · Advertising" to 25L,
            "Google · Analytics" to 99L)
        assertEquals(55L, AdwareScan.adLookups(counts))
        assertNotNull(AdwareScan.score(sig(ads = 55)).second.firstOrNull())
    }

    /** Real sites the detector wrongly flagged in a scan of the top 100,000
     *  websites (+160k hostnames in total) — must never be flagged again. */
    @Test fun realSitesFromTop100kScanNotFlagged() {
        for (h in listOf("wal-mart.com", "www.wal-mart.com", "1cloud.ru",
                         "watson.telemetry.microsoft.com.nsatc.net"))
            assertNull(h, FakeSites.lookalikeOf(h))
        // and the real fakes are still caught
        for (h in listOf("paypa1.com", "wa1mart.com", "icloud.com.verify-id.top", "netflix-billing-update.com"))
            assertNotNull(h, FakeSites.lookalikeOf(h))
    }
}
