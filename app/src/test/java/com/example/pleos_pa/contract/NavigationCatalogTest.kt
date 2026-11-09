package com.example.pleos_pa.contract

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins `contracts/navigation/catalog.json` to NaviHelper on the classpath and to the Manifest. */
class NavigationCatalogTest {
    private val catalog = NavigationFixtures.catalog

    @Test
    fun everySdkCallIsAPublicNoArgumentNaviHelperMethod() {
        val helper = Class.forName("ai.pleos.playground.navi.helper.NaviHelper")
        for (tool in catalog.tools) {
            val method = helper.getMethod(tool.sdkCall)
            assertEquals(tool.name, Void.TYPE, method.returnType)
        }
    }

    @Test
    fun toolsAreNamedAndRecordedConsistently() {
        for (tool in catalog.tools) {
            assertTrue(tool.name, tool.name.startsWith("get_"))
            assertTrue(tool.name, tool.adoption in setOf("undecided", "adopted", "rejected"))
            assertTrue(tool.name, tool.permission == "NAVI_ROUTE")
        }
        assertEquals(catalog.tools.size, catalog.tools.map { it.name }.toSet().size)
        // Adoption follows an emulator run, which the catalog records.
        if (catalog.tools.any { it.adoption == "adopted" }) {
            assertTrue(catalog.verifiedOn != null)
        }
    }

    @Test
    fun limitsArePositive() {
        assertTrue(catalog.deadlineMs > 0)
        assertTrue(catalog.staleHoldMs > 0)
        assertEquals("NaviHelper 3.0.5", catalog.sdk)
    }

    @Test
    fun theManifestDeclaresWhatAdoptedToolsReadAndNothingElse() {
        val needed = catalog.tools.filter { it.adoption == "adopted" }.map { it.permission }.toSet()
        val declared =
            Regex("""pleos\.car\.permission\.(NAVI_\w+)""")
                .findAll(File("src/main/AndroidManifest.xml").readText())
                .map { it.groupValues[1] }
                .toSet()
        // NAVI_ROUTE_SEARCH would allow route changes; it must never be requested for reads.
        assertEquals(needed.sorted(), declared.sorted())
    }
}
