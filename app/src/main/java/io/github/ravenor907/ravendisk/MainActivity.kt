package io.github.ravenor907.ravendisk

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.plan.EraseMethod
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.run.Runner
import io.github.ravenor907.ravendisk.ui.App
import io.github.ravenor907.ravendisk.ui.AppModel
import io.github.ravenor907.ravendisk.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var receiver: BroadcastReceiver? = null
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system-bar icons always: the app is dark whatever the phone's theme.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val app = application as RavenApp
        // Debug builds accept a "demo" extra that opens a screen on simulated drives (CI screenshots).
        val demo = if (app.debuggable) intent.getStringExtra("demo") else null
        model = AppModel(this, app, demo != null)
        receiver = model.drives.registerReceiver { model.refresh() }
        setContent {
            val run by Runner.state.collectAsState()
            LaunchedEffect(Unit) {
                model.refresh()
                if (demo != null) startDemo(demo)
            }
            // Keep the screen on while writing, like Handheld Forge does.
            LaunchedEffect(run?.running) {
                if (run?.running == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val m = remember { model }
            BackHandler(enabled = m.stack.size > 1) { m.pop() }
            App(m)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A drive was plugged in and the person chose this app: read it.
        if (::model.isInitialized) model.refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized && !Runner.busy) model.refresh()
    }

    override fun onDestroy() {
        receiver?.let { model.drives.unregisterReceiver(it) }
        super.onDestroy()
    }

    private suspend fun startDemo(demo: String) {
        // Wait for the first refresh to list the simulated drives.
        var waited = 0
        while (model.drives.drive("demo:ssd") == null && waited++ < 300) delay(100)
        val ssd = "demo:ssd"
        when (demo) {
            "device" -> model.push(Screen.Device(ssd))
            "flash" -> model.push(Screen.Device("demo:flash"))
            "hdd" -> model.push(Screen.Device("demo:hdd"))
            "capabilities" -> model.push(Screen.Capabilities)
            "log" -> model.push(Screen.Log)
            "create", "format", "erase", "table" -> {
                model.demoSheet = demo
                model.push(Screen.Device(if (demo == "table" || demo == "create") "demo:hdd" else ssd))
            }
            "review" -> {
                val p = model.planner(ssd) ?: return
                p.add(Op.Delete(2))
                p.add(Op.Format(4, FsType.EXFAT, "Library"))
                p.add(Op.SetLabel(1, "BOOT"))
                model.planChanged()
                model.push(Screen.Device(ssd))
                model.push(Screen.Review(ssd))
            }
            "review-table" -> {
                val p = model.planner("demo:hdd") ?: return
                p.add(Op.NewTable(io.github.ravenor907.ravendisk.core.table.TableKind.GPT))
                val half = p.snap.blockCount / 2 / 2048 * 2048
                p.add(Op.Create(2048, half - 1, FsType.EXFAT, "Media"))
                model.planChanged()
                model.push(Screen.Device("demo:hdd"))
                model.push(Screen.Review("demo:hdd"))
            }
            "run", "done" -> {
                val p = model.planner(ssd) ?: return
                if (demo == "run") p.add(Op.Erase(2, EraseMethod.ZERO)) else p.add(Op.SetLabel(1, "BOOT"))
                model.planChanged()
                model.push(Screen.Device(ssd))
                model.push(Screen.Review(ssd))
                model.startRun(ssd)
            }
        }
    }
}
