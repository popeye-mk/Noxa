package com.guardian.app

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.security.MessageDigest

/**
 * v1.14 one-tap update. Android allows no silent install of a sideloaded app,
 * so this is the shortest honest path: the "new version" notification (or the
 * tools menu) opens this screen, Android's download manager fetches the APK
 * from the GitHub release, its SHA-256 is checked against the .sha256 file
 * published next to it, and Android's own installer opens — one tap on
 * "Install". The first time, Android asks once to allow Noxa to install
 * updates (Settings → "Install unknown apps").
 *
 * Only a copy signed with the release key gets here (AppUpdater); F-Droid and
 * TEST builds update from where they came.
 */
class UpdateActivity : Activity() {

    private var downloadId = -1L
    private var expectedSha = ""
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val shaUrl = intent.getStringExtra(EXTRA_SHA_URL).orEmpty()
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        if (url.isEmpty() || !url.startsWith("https://github.com/popeye-mk/Noxa/releases/download/")) { finish(); return }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Noxa to install its updates, then tap the notification again.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            finish(); return
        }
        Toast.makeText(this, "Downloading Noxa $version…", Toast.LENGTH_SHORT).show()
        Thread {
            expectedSha = if (shaUrl.isEmpty()) "" else AppUpdater.fetchText(shaUrl)?.trim()?.substringBefore(" ")?.lowercase().orEmpty()
            runOnUiThread { startDownload(url, version) }
        }.start()
    }

    private fun startDownload(url: String, version: String) {
        val dm = getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Noxa $version")
            .setDescription("Downloading the update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "noxa-$version.apk")
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                onDownloaded(dm)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        else registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        downloadId = try { dm.enqueue(req) } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start the download: ${e.message}", Toast.LENGTH_LONG).show(); finish(); -1L
        }
    }

    private fun onDownloaded(dm: DownloadManager) {
        val uri = dm.getUriForDownloadedFile(downloadId)
        if (uri == null) { Toast.makeText(this, "The download failed.", Toast.LENGTH_LONG).show(); finish(); return }
        Thread {
            val ok = try {
                dm.openDownloadedFile(downloadId).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use { AppUpdater.shaMatches(expectedSha, sha256(it)) }
                }
            } catch (e: Exception) { Log.w("Guardian", "update check: $e"); false }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "The downloaded file failed its integrity check — not installed.", Toast.LENGTH_LONG).show()
                    dm.remove(downloadId); finish(); return@runOnUiThread
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open the installer: ${e.message}", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_URL = "apk_url"
        const val EXTRA_SHA_URL = "sha_url"
        const val EXTRA_VERSION = "version"
    }
}
