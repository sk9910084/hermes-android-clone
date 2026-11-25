package com.hermes.clone.agent

/**
 * Streaming APIs deliver a tool call in fragments: the id and name arrive first, the JSON
 * arguments arrive in pieces. This class stitches them together. It also copes with providers
 * that omit ids or reuse index 0 for several parallel calls.
 */
class ToolCallAssembler {
    private class Builder(var id: String?) {
        var name: String = ""
        val args: StringBuilder = StringBuilder()
    }

    private val builders = LinkedHashMap<Int, Builder>()

    fun accept(delta: DeltaToolCall) {
        val key = resolveKey(delta)
        val builder = builders.getOrPut(key) { Builder(delta.id) }
        if (builder.id == null && delta.id != null) builder.id = delta.id
        val fragmentName = delta.function?.name
        if (!fragmentName.isNullOrEmpty() && builder.name.isEmpty()) builder.name = fragmentName
        val fragmentArgs = delta.function?.arguments
        if (fragmentArgs != null) builder.args.append(fragmentArgs)
    }

    private fun resolveKey(delta: DeltaToolCall): Int {
        val id = delta.id
        if (id != null) {
            val sameId = builders.entries.firstOrNull { it.value.id == id }
            if (sameId != null) return sameId.key
            val atIndex = builders[delta.index]
            if (atIndex != null && atIndex.id != null && atIndex.id != id) {
                return (builders.keys.maxOrNull() ?: -1) + 1
            }
        }
        return delta.index
    }

    fun build(): List<ToolCall> =
        builders.values
            .filter { it.name.isNotBlank() }
            .mapIndexed { i, b ->
                ToolCall(
                    id = b.id ?: "call_${System.nanoTime()}_$i",
                    function = FunctionCall(name = b.name, arguments = b.args.toString().ifBlank { "{}" }),
                )
            }
}
