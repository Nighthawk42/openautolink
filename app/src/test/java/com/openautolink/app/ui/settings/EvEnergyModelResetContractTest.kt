package com.openautolink.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvEnergyModelResetContractTest {
    @Test
    fun `reset UI awaits completion and reports every outcome honestly`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelViewModel.kt",
        ).readText()
        val reset = source.substringAfter("fun resetLearnedRate()")
            .substringBefore("// ── Phase 2")

        assertTrue(reset.contains("viewModelScope.launch"))
        assertTrue(reset.contains("when (learnedEstimator.reset(activeKey))"))
        assertTrue(reset.contains("ResetResult.COMPLETED -> \"Learned rate reset\""))
        assertTrue(reset.contains("ResetResult.PERSIST_PENDING -> \"Learned rate cleared; save will retry\""))
        assertTrue(reset.contains("ResetResult.REJECTED_BUSY -> \"Reset busy; try again\""))
        assertTrue(reset.contains("ResetResult.REJECTED_STOPPING -> \"Reset unavailable while stopping\""))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("project file not found: $path")
    }
}
