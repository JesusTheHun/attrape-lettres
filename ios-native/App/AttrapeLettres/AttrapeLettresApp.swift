import ALArt
import ALCore
import ALUI
import SwiftUI

// The whole app shell. Everything of substance lives in the SwiftPM package one
// directory up, which is what keeps it testable on the host — see DECISIONS.md
// D1. Resist adding code here; if it belongs to the app it belongs in a target.

@main
struct AttrapeLettresApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
