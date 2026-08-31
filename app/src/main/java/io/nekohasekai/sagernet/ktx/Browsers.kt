package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import io.nekohasekai.sagernet.R

fun Context.launchCustomTab(link: String): Boolean {
    val uri = Uri.parse(link)
    val customTabsIntent = CustomTabsIntent.Builder().apply {
        setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
        setColorSchemeParams(
            CustomTabsIntent.COLOR_SCHEME_LIGHT,
            CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(getColorAttr(R.attr.colorPrimary))
            }.build()
        )
        setColorSchemeParams(
            CustomTabsIntent.COLOR_SCHEME_DARK,
            CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(getColorAttr(R.attr.colorPrimary))
            }.build()
        )
    }.build()
    return try {
        if (customTabsIntent.intent.resolveActivity(packageManager) != null) {
            customTabsIntent.launchUrl(this, uri)
            true
        } else {
            launchBrowser(uri)
        }
    } catch (_: ActivityNotFoundException) {
        launchBrowser(uri)
    }
}

private fun Context.launchBrowser(uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        if (this@launchBrowser !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        if (intent.resolveActivity(packageManager) == null) return false
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
