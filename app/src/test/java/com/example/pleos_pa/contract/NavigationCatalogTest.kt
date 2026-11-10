package com.example.pleos_pa.contract

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins `contracts/navigation/catalog.json` to NaviHelper on the classpath and to the Manifest. */
class NavigationCatalogTest {
    private val catalog = NavigationFixtures.catalog

    @Test
    fun everySdkCallIsAPublicNaviHelperMethod() {
        val helper = Class.forName("ai.pleos.playground.navi.helper.NaviHelper")
        val routeInfo = Class.forName("ai.pleos.playground.navi.data.RouteInfo")
        for (tool in catalog.tools) {
            val call = tool.sdkCall ?: continue
            // A read takes no argument; the one change hands over a RouteInfo.
            val method =
                if (tool.kind == NavigationCatalog.KIND_CHANGE) helper.getMethod(call, routeInfo)
                else helper.getMethod(call)
            assertEquals(tool.name, Void.TYPE, method.returnType)
        }
    }

    @Test
    fun toolsAreNamedAndRecordedConsistently() {
        for (tool in catalog.tools) {
            assertTrue(tool.name, "_navigation_" in tool.name)
            assertTrue(tool.name, tool.adoption in setOf("undecided", "adopted", "rejected"))
            when (tool.kind) {
                NavigationCatalog.KIND_READ -> {
                    assertTrue(tool.name, tool.name.startsWith("get_") || tool.sdkCall == null)
                    // A read reaches the SDK with NAVI_ROUTE or not at all.
                    assertEquals(tool.name, tool.sdkCall?.let { "NAVI_ROUTE" }, tool.permission)
                }
                NavigationCatalog.KIND_CHANGE -> {
                    assertTrue(tool.name, tool.name.startsWith("set_"))
                    assertEquals(tool.name, "NAVI_ROUTE_SEARCH", tool.permission)
                }
                else -> throw AssertionError("${tool.name}: unknown kind ${tool.kind}")
            }
        }
        assertEquals(catalog.tools.size, catalog.tools.map { it.name }.toSet().size)
        // Adoption follows an emulator run, which the catalog records.
        if (catalog.tools.any { it.adoption == "adopted" }) {
            assertTrue(catalog.verifiedOn != null)
        }
    }

    /** The two destination tools work as a pair: a place can only be set if it can be found. */
    @Test
    fun theDestinationToolsAreAdoptedTogether() {
        assertEquals(
            catalog.tool("find_navigation_place")!!.adoption,
            catalog.tool("set_navigation_destination")!!.adoption,
        )
    }

    @Test
    fun limitsArePositive() {
        assertTrue(catalog.deadlineMs > 0)
        assertTrue(catalog.staleHoldMs > 0)
        assertTrue(catalog.baselineDeadlineMs > 0)
        assertTrue(catalog.confirmPollMs > 0)
        assertTrue(catalog.confirmTimeoutMs > catalog.confirmPollMs)
        assertEquals("NaviHelper 3.0.5", catalog.sdk)
    }

    /**
     * The tolerance has to cover the half metre the stock app was seen to move a coordinate by
     * (COM-36) and still tell any two listed places apart.
     */
    @Test
    fun theMatchToleranceSeparatesEveryListedPlace() {
        val tolerance = catalog.matchToleranceDegrees
        assertTrue(tolerance >= 5e-6)
        val places = NavigationFixtures.places.places
        for (place in places) {
            for (other in places) {
                val fields = DestinationFields("", "", other.latitude, other.longitude, 0, 0)
                assertEquals(
                    "${place.key} ${other.key}",
                    place === other,
                    DestinationMatch.matches(place, fields, tolerance),
                )
            }
        }
    }

    @Test
    fun theManifestDeclaresWhatTheCatalogedToolsNeedAndNothingElse() {
        // A tool not yet adopted is not offered, but its SDK call is in the app, and lint requires
        // the permission of every SDK call in the code. Only a rejected tool's permission goes.
        val needed =
            catalog.tools.filter { it.adoption != "rejected" }.mapNotNull { it.permission }.toSet()
        val declared =
            Regex("""pleos\.car\.permission\.(NAVI_\w+)""")
                .findAll(File("src/main/AndroidManifest.xml").readText())
                .map { it.groupValues[1] }
                .toSet()
        // NAVI_ROUTE_SEARCH allows route changes; it is requested only for the cataloged change.
        assertEquals(needed.sorted(), declared.sorted())
    }
}
