package io.github.ravenor907.ravendisk.run

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.ravenor907.ravendisk.core.plan.DiskSession
import io.github.ravenor907.ravendisk.core.plan.Executor
import io.github.ravenor907.ravendisk.core.plan.Journal
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.plan.RunListener
import io.github.ravenor907.ravendisk.core.plan.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a run is, for the run screen and the notification. */
data class RunState(
    val driveKey: String,
    val driveName: String,
    val ops: List<String>,
    val stepIndex: Int = 0,
    val stepCount: Int = 1,
    val stepTitle: String = "Starting",
    val done: Long = 0,
    val total: Long = 0,
    val log: List<String> = emptyList(),
    val stopping: Boolean = false,
    val result: RunResult? = null,
) {
    val running get() = result == null

    /** Overall fraction: whole steps done plus the current step's own progress. */
    val fraction: Float get() {
        if (result != null) return 1f
        val inStep = if (total > 0) done.toFloat() / total else 0f
        return ((stepIndex + inStep) / stepCount.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/**
 * Runs one plan at a time, off the main thread, inside a foreground service so Android doesn't
 * stop the app mid-write. Survives the activity being recreated.
 */
object Runner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state

    @Volatile
    private var cancel = false

    val busy: Boolean get() = _state.value?.running == true

    fun start(context: Context, driveKey: String, driveName: String, session: DiskSession, planner: Planner, journal: Journal, onDone: (RunResult) -> Unit) {
        if (busy) return
        cancel = false
        val ops = planner.queue.map { it.describe(planner.snap.blockSize) }
        _state.value = RunState(driveKey, driveName, ops)
        try {
            ContextCompat.startForegroundService(context, Intent(context, OpService::class.java))
        } catch (e: Exception) {
            // Some phones refuse a foreground service here; the run continues while the app stays open.
            log("Couldn't start the background service (${e.javaClass.simpleName}); keep the app open")
        }
        scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val result = Executor(session, planner, object : RunListener {
                override fun step(index: Int, count: Int, title: String) {
                    _state.value = _state.value?.copy(stepIndex = index, stepCount = count, stepTitle = title, done = 0, total = 0)
                }

                override fun progress(done: Long, total: Long) {
                    val s = _state.value ?: return
                    // Throttle: only publish visible changes.
                    if (total == 0L || s.total != total || (done - s.done) * 200 >= total || done == total) {
                        _state.value = s.copy(done = done, total = total)
                    }
                }

                override fun log(line: String) = this@Runner.log(line)
            }, journal) { cancel }.run()
            android.util.Log.i("RavenDisk", "run finished in ${android.os.SystemClock.elapsedRealtime() - t0} ms: ${result.outcome}")
            _state.value = _state.value?.copy(result = result, stopping = false)
            onDone(result)
        }
    }

    private fun log(line: String) {
        val s = _state.value ?: return
        _state.value = s.copy(log = (s.log + line).takeLast(200))
    }

    /** Stops at the next safe point (between steps, or inside an overwrite). */
    fun stop() {
        cancel = true
        _state.value = _state.value?.copy(stopping = true)
    }

    /** Clears a finished run from the screen. */
    fun dismiss() {
        if (!busy) _state.value = null
    }
}
