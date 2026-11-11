package com.example.pleos_pa.placecheck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCheckVerdictTest {
    @Test
    fun theSameCoordinatesPass() {
        assertTrue(PlaceCheckVerdict.judge(37.5547, 126.9707, 37.5547, 126.9707).pass)
    }

    @Test
    fun aDifferenceWithinTheBoundPasses() {
        assertTrue(PlaceCheckVerdict.judge(37.5547, 126.9707, 37.5547009, 126.9706991).pass)
    }

    @Test
    fun aDifferenceBeyondTheBoundFailsOnEitherAxis() {
        assertFalse(PlaceCheckVerdict.judge(37.5547, 126.9707, 37.554702, 126.9707).pass)
        assertFalse(PlaceCheckVerdict.judge(37.5547, 126.9707, 37.5547, 126.970698).pass)
    }

    @Test
    fun anIdPointingElsewhereFails() {
        // COM-8: Gangnam coordinates with a Seongnam place ID ended at the ID's location.
        assertFalse(
            PlaceCheckVerdict.judge(37.48544722, 127.03636666, 37.41289678, 127.09383464).pass
        )
    }

    @Test
    fun noDestinationNeedsBothAnEmptyNameAndTheOrigin() {
        assertTrue(PlaceCheckVerdict.isNoDestination(" ", 0.0, 0.0))
        assertFalse(PlaceCheckVerdict.isNoDestination("", 37.5547, 126.9707))
        assertFalse(PlaceCheckVerdict.isNoDestination("서울역", 0.0, 0.0))
    }
}
