package examples

import preview.annotations.PreviewParameterProvider

data class User(val name: String, val age: Int)

/**
 * Example provider that supplies test users for previews.
 */
class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice Anderson", 25),
        User("Bob Builder", 35),
        User("Charlie Chaplin", 45)
    )
}
