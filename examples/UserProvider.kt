package examples

import preview.annotations.PreviewParameterProvider

data class User(val name: String, val age: Int)

/**
 * Example provider that supplies test users for previews.
 *
 * Demonstrates custom display names via getDisplayName().
 */
class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    // Materialize to list for efficient getDisplayName() access
    private val userList = listOf(
        User("Alice Anderson", 25),
        User("Bob Builder", 35),
        User("Charlie Chaplin", 45)
    )

    override val values = userList.asSequence()

    override fun getDisplayName(index: Int): String? {
        return userList.getOrNull(index)?.name
    }
}
