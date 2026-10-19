package io.github.ravenor907.ravendisk

import android.app.Application
import android.content.pm.ApplicationInfo
import io.github.ravenor907.ravendisk.backend.Drives
import io.github.ravenor907.ravendisk.core.plan.TextJournal
import java.io.File

/** Process-wide state that must outlive activity recreation: open drives, plans, the journal. */
class RavenApp : Application() {
    lateinit var journal: TextJournal
        private set

    private var drivesInstance: Drives? = null

    val debuggable: Boolean get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate() {
        super.onCreate()
        journal = TextJournal(File(filesDir, "operations.log"))
    }

    /** Created once; the demo flag only matters on first creation (debug builds). */
    fun drives(demo: Boolean): Drives = drivesInstance ?: Drives(this, demo).also { drivesInstance = it }
}
