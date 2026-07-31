package fr.dappit.attrapelettres

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import fr.dappit.attrapelettres.core.rewards.REWARD_CURVE

/**
 * The composition root, and close to nothing else — the twin of
 * App/AttrapeLettresApp.swift.
 *
 * This is the ONE place allowed to see both :ui and :platform: it is where a
 * real device adapter (SharedPreferences, the audio graph, Play Billing) gets
 * wired into an interface that :ui only knows abstractly. Game logic does not
 * live here, and neither does a screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Scaffolding() }
    }
}

/** Placeholder until :ui has a hub. Reads :core so the module wiring is proven, not assumed. */
@Composable
private fun Scaffolding() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEFE6DA)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Attrape-Lettres — ${REWARD_CURVE.joinToString(" → ")}")
    }
}
