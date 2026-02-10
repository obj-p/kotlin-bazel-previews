package preview

data class ParsedArgs(
    val workspaceRoot: String,
    val filePath: String,
    val watch: Boolean,
    val profile: Boolean = false
)

fun parseArgs(args: Array<String>): ParsedArgs {
    val watch = args.contains("--watch")
    val profile = args.contains("--profile")
    val positional = args.filter { it != "--watch" && it != "--profile" }

    for (arg in positional) {
        require(!arg.startsWith("--")) { "Unknown flag: $arg" }
    }

    require(positional.size == 2) {
        "Usage: preview-tool [--watch] [--profile] <workspaceRoot> <kotlinFilePath>"
    }

    return ParsedArgs(
        workspaceRoot = positional[0],
        filePath = positional[1],
        watch = watch,
        profile = profile
    )
}
