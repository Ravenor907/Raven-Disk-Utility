package io.github.ravenor907.ravendisk.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ravenor907.ravendisk.RavenApp
import io.github.ravenor907.ravendisk.backend.Drives
import io.github.ravenor907.ravendisk.backend.Platform
import io.github.ravenor907.ravendisk.backend.PlatformInfo
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.plan.TextJournal
import io.github.ravenor907.ravendisk.run.Runner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object Dashboard : Screen()
    data class Device(val key: String) : Screen()
    data class Review(val key: String) : Screen()
    data class Run(val key: String) : Screen()
    data class Volume(val id: String) : Screen()
    object Capabilities : Screen()
    object Log : Screen()
}

/** UI state and the actions screens call. Lives as long as the activity's composition. */
class AppModel(private val context: Context, val app: RavenApp, demo: Boolean) {
    val drives: Drives = app.drives(demo).also { if (demo) it.demo = true }

    /** Debug demo: a sheet to open on the first device screen. */
    var demoSheet: String? = null
    val journal: TextJournal get() = app.journal
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var stack by mutableStateOf(listOf<Screen>(Screen.Dashboard))
        private set
    val screen: Screen get() = stack.last()

    /** Bumped whenever a plan changes, so screens re-read it. */
    var planVersion by mutableIntStateOf(0)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var platform by mutableStateOf<PlatformInfo?>(null)
        private set
    var interrupted by mutableStateOf<TextJournal.Run?>(null)
        private set
    var simulated by mutableStateOf(drives.demo)
        private set

    /** Unmount progress for one drive: key to result (null result = working on it). */
    var unmounting by mutableStateOf<Pair<String, Drives.Unmount?>?>(null)
        private set

    fun push(s: Screen) {
        stack = stack + s
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    /** Replaces the top screen (e.g. review -> run). */
    fun replace(s: Screen) {
        stack = stack.dropLast(1) + s
    }

    fun home() {
        stack = listOf(Screen.Dashboard)
    }

    fun refresh(reinspect: Set<String> = emptySet()) {
        if (Runner.busy) return
        refreshing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                drives.refresh(reinspect)
                val p = Platform.read(context)
                val i = journal.interrupted()
                withContext(Dispatchers.Main) {
                    platform = p
                    interrupted = i
                }
            }
            refreshing = false
        }
    }

    fun planner(key: String): Planner? = drives.planner(key)

    fun planChanged() {
        planVersion++
    }

    fun discardPlan(key: String) {
        drives.discardPlan(key)
        planChanged()
    }

    fun inspectAnyway(key: String) {
        drives.force(key)
        refresh()
    }

    fun requestPermission(key: String) = drives.requestPermission(key)

    /** Takes the drive away from Android so its partitions can be changed. */
    fun unmount(key: String) {
        if (Runner.busy || unmounting?.second == null && unmounting != null) return
        unmounting = key to null
        scope.launch {
            val r = withContext(Dispatchers.IO) { drives.unmount(key) }
            planChanged()
            unmounting = key to r
        }
    }

    fun unmountAnyway(key: String) {
        unmounting = key to null
        scope.launch {
            withContext(Dispatchers.IO) { drives.unmountAnyway(key) }
            planChanged()
            unmounting = key to Drives.Unmount.DONE
        }
    }

    fun showSimulated(on: Boolean) {
        drives.demo = on
        simulated = on
        refresh()
    }

    fun acknowledgeInterrupted() {
        scope.launch(Dispatchers.IO) { journal.acknowledgeInterrupted() }
        interrupted = null
    }

    /** Starts the queued plan for [key]. The run screen follows it. */
    fun startRun(key: String) {
        val d = drives.drive(key) ?: return
        val session = drives.session(key) ?: return
        val planner = drives.planner(key) ?: return
        Runner.start(context, key, d.title, session, planner, journal) { result ->
            // Re-read the drive whatever happened, so the workspace shows what's really on it now.
            scope.launch {
                withContext(Dispatchers.IO) {
                    drives.discardPlan(key)
                    drives.refresh(setOf(key))
                }
                planChanged()
                if (result.outcome == Outcome.FAILED || result.outcome == Outcome.PARTIAL) interrupted = null
            }
        }
        replace(Screen.Run(key))
    }
}
