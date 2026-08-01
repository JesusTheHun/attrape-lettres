package fr.dappit.attrapelettres.platform

// ---------------------------------------------------------------------------
// The system clock adapter — deliberately a re-export, not a second class.
//
// Unlike SharedPreferences or PackageManager, the wall clock needs nothing
// from the Android runtime: System.currentTimeMillis() is plain JVM, so :core
// already ships the production implementation next to the TimeSource
// interface (core/platform/TimeSource.kt), where its own tests pin it to
// epoch MILLISECONDS. Writing a second implementation here would be a clock
// that could silently drift from the one :core's licensing tests verify —
// the 14-day trial arithmetic must run against the exact object the tests
// ran against.
//
// The alias exists so the composition root wires every system adapter from
// this one package, and so this module's file list mirrors the iOS ALPlatform
// sources one to one.
// ---------------------------------------------------------------------------

typealias SystemTimeSource = fr.dappit.attrapelettres.core.platform.SystemTimeSource
