package fr.dappit.attrapelettres.core.platform

/* -------------------------------------------------------------------------- */
/* Replaces Vite's `__APP_VERSION__`. Telemetry stamps it on every event, and   */
/* nothing in :core may reach into an Android `PackageInfo` for it — :core      */
/* stays pure (A1); the adapter in :platform reads `versionName`.               */
/* -------------------------------------------------------------------------- */

interface AppVersionProvider {
    /** The marketing version, e.g. "0.1.0". */
    val marketing: String
}

/** Tests and previews. */
class FixedAppVersion(override val marketing: String) : AppVersionProvider
