// The iOS side of every protocol ALCore declares. See DECISIONS.md D4.
//
// Nothing here is imported by ALUI or ALArt — they only ever see the protocol.
// That is what lets `swift test` run the app's logic with no store, no network
// and no signing.
enum ALPlatformScaffold {}
