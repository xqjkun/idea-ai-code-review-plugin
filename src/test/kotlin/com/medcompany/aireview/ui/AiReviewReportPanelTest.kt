package com.medcompany.aireview.ui

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiReviewReportPanelTest {
    @Test
    fun `resolves a reported file inside the project`() {
        assertEquals(
            Paths.get("/workspace/project/src/main/App.java"),
            resolveFindingPath("/workspace/project", "a/src/main/App.java"),
        )
    }

    @Test
    fun `rejects a reported path outside the project`() {
        assertNull(resolveFindingPath("/workspace/project", "../../private.txt"))
    }

    @Test
    fun `shows a compact file name in the navigation link`() {
        assertEquals("App.java", displayFileName("module/src/main/App.java"))
    }
}
