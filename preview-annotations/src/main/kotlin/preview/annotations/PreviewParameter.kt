package preview.annotations

import kotlin.reflect.KClass

/**
 * Annotation to mark a preview function parameter that should receive values
 * from a [PreviewParameterProvider].
 *
 * This enables generating multiple preview variants from a single preview function,
 * with each variant using a different value from the provider.
 *
 * Example:
 * ```kotlin
 * class UserProvider : PreviewParameterProvider<User> {
 *     override val values = sequenceOf(
 *         User("Alice", 25),
 *         User("Bob", 35)
 *     )
 * }
 *
 * @Preview
 * fun userPreview(@PreviewParameter(UserProvider::class) user: User): String {
 *     return "User: ${user.name}, Age: ${user.age}"
 * }
 * ```
 *
 * This generates two previews: one with Alice and one with Bob.
 *
 * @param provider The [PreviewParameterProvider] class that supplies values for this parameter
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>
)
