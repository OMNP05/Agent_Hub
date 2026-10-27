package dev.agenthub.app.agents.oauth

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens the consent page in a Chrome Custom Tab, never an embedded WebView. Returns false if it could not open. */
fun launchCustomTab(context: Context, url: String): Boolean {
    val uri = url.toUri()
    if (uri.scheme != "https") return false
    return try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
