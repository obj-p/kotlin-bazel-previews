# PreviewProvider Usage Examples

This document shows concrete examples of how developers would use the PreviewProvider feature with each proposed approach.

---

## Approach 1: Full Compose-Style Implementation

### Basic Single-Parameter Provider

```kotlin
package examples

// 1. Define your data model
data class User(val name: String, val age: Int, val isActive: Boolean = true)

// 2. Create a provider
class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    private val users = listOf(
        User("Alice Anderson", age = 25, isActive = true),
        User("Bob Builder", age = 35, isActive = true),
        User("Charlie Chaplin", age = 45, isActive = false)
    )

    override val values: Sequence<User> = users.asSequence()

    override fun getDisplayName(index: Int): String? {
        return users.getOrNull(index)?.let { user ->
            "${user.name} (${if (user.isActive) "Active" else "Inactive"})"
        }
    }
}

// 3. Use in preview function
@Preview
fun userCardPreview(
    @PreviewParameter(UserPreviewParameterProvider::class, limit = 2) user: User
): String {
    return """
        ╔════════════════════════╗
        ║ User Card              ║
        ╠════════════════════════╣
        ║ Name: ${user.name.padEnd(16)} ║
        ║ Age:  ${user.age.toString().padEnd(16)} ║
        ║ Status: ${if (user.isActive) "✓ Active" else "✗ Inactive"}    ║
        ╚════════════════════════╝
    """.trimIndent()
}
```

**Output JSON:**
```json
{
  "functions": [
    {
      "name": "userCardPreview",
      "results": [
        {
          "displayName": "userCardPreview [Alice Anderson (Active)]",
          "result": "╔════════════════════════╗\n║ User Card              ║\n..."
        },
        {
          "displayName": "userCardPreview [Bob Builder (Active)]",
          "result": "╔════════════════════════╗\n║ User Card              ║\n..."
        }
      ]
    }
  ]
}
```

### Multiple States Provider

```kotlin
// Provider for different UI states
class LoadingStatePreviewParameterProvider : PreviewParameterProvider<LoadingState> {
    override val values = sequenceOf(
        LoadingState.Loading,
        LoadingState.Success(data = "Sample data loaded successfully"),
        LoadingState.Error(message = "Network connection failed")
    )

    override fun getDisplayName(index: Int): String? {
        return when (index) {
            0 -> "Loading State"
            1 -> "Success State"
            2 -> "Error State"
            else -> null
        }
    }
}

sealed class LoadingState {
    object Loading : LoadingState()
    data class Success(val data: String) : LoadingState()
    data class Error(val message: String) : LoadingState()
}

@Preview
fun loadingScreenPreview(
    @PreviewParameter(LoadingStatePreviewParameterProvider::class) state: LoadingState
): String {
    return when (state) {
        is LoadingState.Loading -> "⏳ Loading..."
        is LoadingState.Success -> "✓ ${state.data}"
        is LoadingState.Error -> "✗ Error: ${state.message}"
    }
}
```

### Multi-Parameter Provider (Advanced)

```kotlin
// Provider for themes
class ThemePreviewParameterProvider : PreviewParameterProvider<Theme> {
    override val values = sequenceOf(Theme.LIGHT, Theme.DARK)

    override fun getDisplayName(index: Int): String? {
        return if (index == 0) "Light" else "Dark"
    }
}

// Provider for sizes
class SizePreviewParameterProvider : PreviewParameterProvider<Size> {
    override val values = sequenceOf(Size.SMALL, Size.LARGE)

    override fun getDisplayName(index: Int): String? {
        return if (index == 0) "Small" else "Large"
    }
}

enum class Theme { LIGHT, DARK }
enum class Size { SMALL, LARGE }

@Preview
fun buttonPreview(
    @PreviewParameter(ThemePreviewParameterProvider::class) theme: Theme,
    @PreviewParameter(SizePreviewParameterProvider::class) size: Size
): String {
    val bg = if (theme == Theme.LIGHT) "⬜" else "⬛"
    val padding = if (size == Size.SMALL) " " else "  "
    return "$bg${padding}Click Me$padding$bg"
}
```

**Generates 4 previews (2 themes × 2 sizes):**
- `buttonPreview [Light, Small]`
- `buttonPreview [Light, Large]`
- `buttonPreview [Dark, Small]`
- `buttonPreview [Dark, Large]`

### Complex Object Provider

```kotlin
// Provider with complex nested objects
class OrderPreviewParameterProvider : PreviewParameterProvider<Order> {
    override val values = sequenceOf(
        Order(
            id = "ORD-001",
            items = listOf(
                Item("Laptop", price = 999.99),
                Item("Mouse", price = 29.99)
            ),
            customer = Customer("Alice", email = "alice@example.com"),
            status = OrderStatus.PENDING
        ),
        Order(
            id = "ORD-002",
            items = listOf(Item("Keyboard", price = 79.99)),
            customer = Customer("Bob", email = "bob@example.com"),
            status = OrderStatus.SHIPPED
        )
    )

    override fun getDisplayName(index: Int): String? {
        val orders = values.toList()
        return orders.getOrNull(index)?.let { order ->
            "${order.id} - ${order.status}"
        }
    }
}

data class Order(
    val id: String,
    val items: List<Item>,
    val customer: Customer,
    val status: OrderStatus
)

data class Item(val name: String, val price: Double)
data class Customer(val name: String, val email: String)
enum class OrderStatus { PENDING, SHIPPED, DELIVERED }

@Preview
fun orderSummaryPreview(
    @PreviewParameter(OrderPreviewParameterProvider::class) order: Order
): String {
    val total = order.items.sumOf { it.price }
    return """
        Order: ${order.id}
        Customer: ${order.customer.name}
        Items: ${order.items.size}
        Total: $${"%.2f".format(total)}
        Status: ${order.status}
    """.trimIndent()
}
```

---

## Approach 2: Simplified Provider

### Basic Usage (No Limit, No Display Names)

```kotlin
// Simplified interface - no getDisplayName()
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
}

class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice", 25),
        User("Bob", 35),
        User("Charlie", 45)
    )
}

// No limit parameter in annotation
@Preview
fun userCardPreview(
    @PreviewParameter(UserPreviewParameterProvider::class) user: User
): String {
    return "User: ${user.name}, Age: ${user.age}"
}
```

**Output JSON (auto-generated display names):**
```json
{
  "functions": [
    {
      "name": "userCardPreview",
      "results": [
        {"displayName": "userCardPreview [1]", "result": "User: Alice, Age: 25"},
        {"displayName": "userCardPreview [2]", "result": "User: Bob, Age: 35"},
        {"displayName": "userCardPreview [3]", "result": "User: Charlie, Age: 45"}
      ]
    }
  ]
}
```

### Limitation: Single Parameter Only

```kotlin
// ✅ ALLOWED: Single parameter
@Preview
fun singleParamPreview(
    @PreviewParameter(UserPreviewParameterProvider::class) user: User
): String = "..."

// ❌ REJECTED: Multiple parameters
@Preview
fun multiParamPreview(
    @PreviewParameter(ThemeProvider::class) theme: Theme,
    @PreviewParameter(SizeProvider::class) size: Size
): String = "..."  // Compilation error or skipped during discovery
```

---

## Approach 3: Factory Function Pattern

### Basic Factory Usage

```kotlin
// Define factory functions (no interface needed)
fun userSamples(): List<User> = listOf(
    User("Alice", 25),
    User("Bob", 35),
    User("Charlie", 45)
)

fun themeSamples(): List<Theme> = listOf(Theme.LIGHT, Theme.DARK)

// Use factory in annotation (string reference)
@Preview
fun userCardPreview(
    @PreviewParameter(factory = "examples.userSamples") user: User
): String {
    return "User: ${user.name}"
}

@Preview
fun themedButtonPreview(
    @PreviewParameter(factory = "examples.themeSamples") theme: Theme
): String {
    return if (theme == Theme.LIGHT) "⬜ Button" else "⬛ Button"
}
```

### Object-Based Factories

```kotlin
// Group related factories in objects
object TestData {
    @JvmStatic
    fun users(): List<User> = listOf(
        User("Alice", 25),
        User("Bob", 35)
    )

    @JvmStatic
    fun orders(): List<Order> = listOf(
        Order("ORD-001", status = OrderStatus.PENDING),
        Order("ORD-002", status = OrderStatus.SHIPPED)
    )
}

@Preview
fun userPreview(
    @PreviewParameter(factory = "examples.TestData.users") user: User
): String = "User: ${user.name}"

@Preview
fun orderPreview(
    @PreviewParameter(factory = "examples.TestData.orders") order: Order
): String = "Order: ${order.id}"
```

### Pros and Cons of Factory Approach

**✅ Pros:**
- Very simple to implement (just string parsing)
- Factory functions can be reused in unit tests
- No interface boilerplate
- Easy to understand

**❌ Cons:**
- String references are fragile (no compile-time checking)
- Refactoring breaks references
- No display name customization
- No limit control

---

## Comparison Table

| Feature | Approach 1 (Full) | Approach 2 (Simplified) | Approach 3 (Factory) |
|---------|-------------------|-------------------------|----------------------|
| Custom display names | ✅ Yes | ❌ No (auto-numbered) | ❌ No (auto-numbered) |
| Limit parameter | ✅ Yes | ❌ No (all values) | ❌ No (all values) |
| Multiple parameters | ✅ Yes (cartesian) | ❌ No (single only) | ⚠️  Possible but complex |
| Type safety | ✅ Strong | ✅ Strong | ❌ Weak (string refs) |
| API complexity | 🟡 Medium | 🟢 Low | 🟢 Low |
| Implementation effort | 🔴 High | 🟡 Medium | 🟢 Low |
| Compose parity | ✅ Full | ⚠️  Partial | ❌ Different approach |

---

## Recommended Starting Point

Start with **Approach 1** (Full Compose-Style) but implement in phases:

### Phase 1: Minimal Viable Product
- Single parameter only
- No display names (use indices)
- No limit (use all values)

```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
}

@Preview
fun preview(@PreviewParameter(Provider::class) data: T): String
```

### Phase 2: Display Names
- Add `getDisplayName()` method
- Update JSON output format

### Phase 3: Limits
- Add `limit` parameter to annotation
- Implement truncation in PreviewRunner

### Phase 4: Multiple Parameters
- Support cartesian product
- Complex display name generation

This incremental approach minimizes risk while providing value early.

---

## Migration Examples

### Before: Multiple Preview Functions

```kotlin
@Preview
fun userPreviewYoung(): String {
    val user = User("Alice", 25)
    return "User: ${user.name}, Age: ${user.age}"
}

@Preview
fun userPreviewMiddle(): String {
    val user = User("Bob", 35)
    return "User: ${user.name}, Age: ${user.age}"
}

@Preview
fun userPreviewSenior(): String {
    val user = User("Charlie", 45)
    return "User: ${user.name}, Age: ${user.age}"
}
```

### After: Single Parameterized Function

```kotlin
class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice", 25),
        User("Bob", 35),
        User("Charlie", 45)
    )

    override fun getDisplayName(index: Int): String? {
        return listOf("Young", "Middle", "Senior").getOrNull(index)
    }
}

@Preview
fun userPreview(
    @PreviewParameter(UserPreviewParameterProvider::class) user: User
): String {
    return "User: ${user.name}, Age: ${user.age}"
}
```

**Benefits:**
- 75% less code (4 functions → 1 function + 1 provider)
- Centralized test data
- Easier to add new cases (just extend values)
- Display names clearly identify each preview
