package com.example.pleos_pa.contract

enum class ReadStage {
    CAPABILITY,
    READ,
}

/** [errorType] is diagnostic only: minified apps report names like `a`. */
data class ClassifiedFailure(val status: ItemStatus, val errorCode: String?, val errorType: String)

/**
 * Maps an SDK `onFailed` exception to an item status. It works without SDK types because read
 * failures reach the app as plain exceptions holding the service's string, and messages are never
 * exported.
 */
object SdkFailureClassifier {
    private const val MAX_CAUSES = 16
    private val AREA_MESSAGE = Regex("""area ID: \S+ not supported for property ID""")
    private val ERROR_TYPE = Regex("""[A-Za-z_$][A-Za-z0-9_$]{0,99}""")

    private val CODES =
        mapOf(
            "30001" to "ERROR_INTERNAL_SERVICE",
            "40001" to "ERROR_SERVICE_CONNECTION_FAILURE",
            "50001" to "ERROR_DRIVING_RESTRICTED",
            "60001" to "ERROR_NOT_SUPPORTED_PROPERTY",
            "60002" to "ERROR_NOT_SUPPORTED_VALUE_TYPE",
            "60003" to "ERROR_NOT_SUPPORTED_AREA",
            "70001" to "ERROR_EMPTY_RESULT",
            "800001" to "ERROR_SECURITY_PERMISSION",
            "99999" to "ERROR_UNDEFINED",
        )

    fun classify(stage: ReadStage, error: Throwable): ClassifiedFailure {
        val errorType = error.javaClass.simpleName.takeIf(ERROR_TYPE::matches) ?: "Exception"
        for (cause in causes(error)) {
            if (cause is SecurityException) {
                return ClassifiedFailure(ItemStatus.PERMISSION_DENIED, null, errorType)
            }
            val message = cause.message?.trim() ?: continue
            val code = codeOf(message)
            if (code != null) return ClassifiedFailure(statusOf(code, stage), code, errorType)
            if (AREA_MESSAGE.containsMatchIn(message)) {
                return ClassifiedFailure(areaStatus(stage), null, errorType)
            }
        }
        return ClassifiedFailure(fallback(stage), null, errorType)
    }

    private fun causes(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < MAX_CAUSES && chain.none { it === current }) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun codeOf(message: String): String? =
        CODES[message] ?: message.takeIf { it in CODES.values }

    private fun statusOf(code: String, stage: ReadStage): ItemStatus =
        when (code) {
            "ERROR_SERVICE_CONNECTION_FAILURE" -> ItemStatus.SERVICE_UNAVAILABLE
            "ERROR_DRIVING_RESTRICTED" -> ItemStatus.DRIVING_RESTRICTED
            "ERROR_NOT_SUPPORTED_PROPERTY",
            "ERROR_NOT_SUPPORTED_VALUE_TYPE" -> ItemStatus.PROPERTY_UNSUPPORTED
            "ERROR_SECURITY_PERMISSION" -> ItemStatus.PERMISSION_DENIED
            "ERROR_NOT_SUPPORTED_AREA" -> areaStatus(stage)
            "ERROR_EMPTY_RESULT" ->
                if (stage == ReadStage.READ) ItemStatus.NO_VALUE else ItemStatus.CAPABILITY_FAILED
            else -> fallback(stage)
        }

    private fun areaStatus(stage: ReadStage): ItemStatus =
        if (stage == ReadStage.READ) ItemStatus.AREA_UNSUPPORTED else ItemStatus.CAPABILITY_FAILED

    private fun fallback(stage: ReadStage): ItemStatus =
        if (stage == ReadStage.READ) ItemStatus.READ_FAILED else ItemStatus.CAPABILITY_FAILED
}
