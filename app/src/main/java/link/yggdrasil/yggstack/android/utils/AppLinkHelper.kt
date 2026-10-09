package link.yggdrasil.yggstack.android.utils

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Checks whether the http(s) link domains this app declares in its manifest
 * (the autoVerify VIEW/BROWSABLE intent filters, e.g. DrewCyber.github.io/)
 * are enabled for this app in Android's "Open by default" settings, and opens
 * that settings page.
 *
 * Android 12+ only: the OS reports the declared domains together with their
 * state. Older releases have no per-domain user toggles (verification is
 * automatic), so there is nothing user-fixable to detect and no check runs.
 */
object AppLinkHelper {

    /**
     * Domains that should open this app but currently won't (link handling
     * turned off or a domain's toggle not enabled). Empty = all fine or
     * nothing checkable on this Android version. Never throws: on unexpected
     * device quirks it reports nothing so the settings nudge never
     * false-positives.
     */
    fun getDisabledLinkDomains(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return try {
            val dvm = context.getSystemService(DomainVerificationManager::class.java)
                ?: return emptyList()
            val state: DomainVerificationUserState =
                dvm.getDomainVerificationUserState(context.packageName) ?: return emptyList()
            val hostStates = state.hostToStateMap
            when {
                hostStates.isEmpty() -> emptyList()
                // Master "Open supported links" switch off: nothing opens in-app.
                !state.isLinkHandlingAllowed -> hostStates.keys.toList()
                // DOMAIN_STATE_NONE = declared but neither verified nor selected
                // by the user; VERIFIED and SELECTED both open links in the app.
                else -> hostStates.filterValues {
                    it == DomainVerificationUserState.DOMAIN_STATE_NONE
                }.keys.toList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Opens the system "Open by default" page for this app; falls back to App Info. */
    fun openOpenByDefaultSettings(context: Context): Boolean {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
            return true
        } catch (t: Exception) {
            // Fall through to App Info
        }
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
            true
        } catch (t: Exception) {
            false
        }
    }
}
