package preview

/**
 * Sealed class hierarchy representing different types of preview generation errors.
 *
 * This provides structured error information that can be used for better error reporting,
 * debugging, and tooling integration.
 */
sealed class PreviewError {
    abstract val message: String
    abstract val category: String

    /**
     * Provider class could not be found on the classpath.
     */
    data class ProviderNotFound(
        val providerClass: String,
        val parameterName: String
    ) : PreviewError() {
        override val category = "provider"
        override val message = "Provider class '$providerClass' not found for parameter '$parameterName'"
    }

    /**
     * Provider class found but could not be instantiated.
     */
    data class ProviderInstantiationFailed(
        val providerClass: String,
        val parameterName: String,
        val cause: String
    ) : PreviewError() {
        override val category = "provider"
        override val message = "Failed to instantiate provider $providerClass for parameter '$parameterName': $cause"
    }

    /**
     * Provider returned no values.
     */
    data class ProviderEmpty(
        val providerClass: String,
        val parameterName: String
    ) : PreviewError() {
        override val category = "provider"
        override val message = "Provider $providerClass for parameter '$parameterName' returned no values"
    }

    /**
     * Parameter types don't match what the preview function expects.
     */
    data class TypeMismatch(
        val functionName: String,
        val expectedTypes: List<String>,
        val providedTypes: List<String>,
        val parameterNames: List<String>
    ) : PreviewError() {
        override val category = "type_mismatch"
        override val message: String
            get() {
                val params = parameterNames.zip(expectedTypes).zip(providedTypes)
                    .joinToString("\n") { (nameType, provided) ->
                        "  ${nameType.first}: expected ${nameType.second}, provided $provided"
                    }
                return "Type mismatch in function '$functionName':\n$params"
            }
    }

    /**
     * Preview method not found with the expected parameter count.
     */
    data class MethodNotFound(
        val functionName: String,
        val expectedParamCount: Int,
        val parameterNames: List<String>
    ) : PreviewError() {
        override val category = "method_not_found"
        override val message = "No method '$functionName' with $expectedParamCount parameters found (expected: ${parameterNames.joinToString(", ")})"
    }

    /**
     * Preview function invocation threw an exception.
     */
    data class InvocationFailed(
        val displayName: String,
        val cause: String
    ) : PreviewError() {
        override val category = "invocation"
        override val message = "Preview $displayName failed: $cause"
    }

    /**
     * Too many parameter combinations generated (exceeds limit of 100).
     */
    data class TooManyCombinations(
        val totalCombinations: Long,
        val parameterInfo: String,
        val limit: Int = 100
    ) : PreviewError() {
        override val category = "combinations_limit"
        override val message = "Too many parameter combinations: $parameterInfo = $totalCombinations (limit: $limit)"
    }
}
