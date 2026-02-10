package preview.annotations

/**
 * Marks a function as a preview function that can be invoked by the preview tool.
 *
 * Preview functions must:
 * - Be public and non-private
 * - Not be suspend or abstract
 * - Have zero parameters OR parameters annotated with @PreviewParameter
 * - Not be extension functions
 * - Not have type parameters
 * - Return a non-Unit type (preferably String for display)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Preview
