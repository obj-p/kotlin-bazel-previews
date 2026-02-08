package preview

data class ParsedArgs(
    val workspaceRoot: String,
    val filePath: String,
    val watch: Boolean,
)

fun parseArgs(args: Array<String>): ParsedArgs {
    val watch = args.contains("--watch")
    val positional = args.filter { it != "--watch" }

    require(positional.size == 2) {
        "Usage: preview-tool [--watch] <workspaceRoot> <kotlinFilePath>"
    }

    return ParsedArgs(
        workspaceRoot = positional[0],
        filePath = positional[1],
        watch = watch,
    )
}
