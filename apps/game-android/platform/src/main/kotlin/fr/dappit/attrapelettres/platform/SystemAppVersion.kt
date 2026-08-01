package fr.dappit.attrapelettres.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import fr.dappit.attrapelettres.core.platform.AppVersionProvider

/**
 * [AppVersionProvider] from the package's `versionName` — the replacement for
 * Vite's `__APP_VERSION__` (the iOS twin reads `CFBundleShortVersionString`).
 *
 * Two consumers: telemetry stamps it on every event (`{ v: … }`), and the
 * native-version guard compares it against a remote content payload's
 * `minNative`. Neither may reach into [PackageManager] itself — :core stays
 * pure (A1), so the `versionName` read happens exactly here.
 *
 * The seam is the constructor: `PackageInfo` cannot be forged in a host test
 * (the class is Android runtime), so the lookup's RESULT — a nullable string —
 * is what the tests drive, the same shape as the iOS `init(infoValue:)`.
 */
class SystemAppVersion internal constructor(rawVersionName: String?) : AppVersionProvider {

    override val marketing: String =
        if (rawVersionName.isNullOrEmpty()) FALLBACK else rawVersionName

    companion object {
        /**
         * What an absent or empty `versionName` reads as. `versionAtLeast`
         * compares it as the lowest possible version, so a build that cannot
         * name its own version can only ever gate MORE conservatively, never
         * claim a minimum it does not satisfy. It must not be "": telemetry
         * stamps `v` on every event and an empty string is a parse hazard on
         * the other end.
         */
        const val FALLBACK = "0.0.0"

        /** The production provider, read once from the app's own package. */
        fun from(context: Context): SystemAppVersion =
            SystemAppVersion(readVersionName(context))

        private fun readVersionName(context: Context): String? =
            try {
                val pm = context.packageManager
                val info =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(
                            context.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        // The flags-object overload only exists from API 33;
                        // below that the deprecated int overload is the only
                        // way to ask, which is why the suppression is scoped
                        // to this branch and nothing else.
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(context.packageName, 0)
                    }
                info.versionName
            } catch (_: Exception) {
                // Asking PackageManager about our own installed package cannot
                // reasonably fail, but if it ever does the answer is the
                // conservative fallback, not a crash at composition time.
                null
            }
    }
}
