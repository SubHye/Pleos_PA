package com.example.pleos_pa.ui

/** Read-only platform evidence. This never grants a UI/vehicle capability. */
data class CarUxObservation(
    val event: String,
    val origin: String,
    val displayId: Int,
    val elapsedMillis: Long,
    val requiresDo: Boolean? = null,
    val activeRestrictions: Int? = null,
    val cleanupCallsSucceeded: Boolean? = null,
    val maxStringLength: Int? = null,
    val maxContentItems: Int? = null,
    val maxContentDepth: Int? = null,
)
