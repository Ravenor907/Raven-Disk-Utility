package io.github.ravenor907.ravendisk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun App(m: AppModel) {
    Box(Modifier.fillMaxSize().background(Raven.Night)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (val s = m.screen) {
                Screen.Dashboard -> DashboardScreen(m)
                is Screen.Device -> DeviceScreen(m, s.key)
                is Screen.Review -> ReviewScreen(m, s.key)
                is Screen.Run -> RunScreen(m, s.key)
                is Screen.Volume -> VolumeScreen(m, s.id)
                Screen.Capabilities -> CapabilitiesScreen(m)
                Screen.Log -> LogScreen(m)
            }
        }
    }
}

/** A scrolling page column, centered and width-limited on tablets and handhelds. */
@Composable
fun Page(maxWidth: Dp = 760.dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = maxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/** Back, title and subtitle, with optional actions on the right. */
@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            LinkButton("‹ Back", onBack)
            HGap(4)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, Raven.h2.copy(fontSize = 20.sp), maxLines = 1)
            if (subtitle != null) Txt(subtitle, Raven.hint, maxLines = 2)
        }
        actions()
    }
}
