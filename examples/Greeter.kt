package examples

annotation class Preview

object Greeter {
    fun greet(name: String): String {
        return "Hello, $name!"
    }

    @Preview
    fun greetDefault(): String {
        return greet("Default")
    }
}

@Preview
fun greetWorld(): String {
    return Greeter.greet("World")
}
