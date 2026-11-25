package com.hermes.clone.agent

import com.hermes.clone.data.SkillsStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Skills the agent may read are exactly the ones the user switched on in the Skills screen. */
private const val NOT_ENABLED_HINT = "Ask the user to enable it in the Skills screen."

/** skills_list: names and descriptions of the enabled skills. */
class SkillsListTool(private val store: SkillsStore) : Tool {
    override val spec: ToolSpec = toolSpec(
        name = "skills_list",
        description = "Lists the skills the user has enabled (name, one-line description, category). " +
            "Use skill_view to read one in full.",
        parameters = objectSchema(
            mapOf("category" to stringProp("Optional category to filter by.")),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val category = args.string("category")?.trim()?.takeIf { it.isNotEmpty() }
        val skills = store.enabledSkills()
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
        if (skills.isEmpty()) {
            return if (category == null) {
                "No skills are enabled. The user can enable skills in the Skills screen."
            } else {
                "No enabled skill is in the category \"$category\"."
            }
        }
        return buildJsonObject {
            put("count", skills.size)
            putJsonArray("skills") {
                skills.forEach { skill ->
                    add(
                        buildJsonObject {
                            put("name", skill.name)
                            put("description", skill.description)
                            put("category", skill.category)
                        },
                    )
                }
            }
        }.toString()
    }
}

/** skill_view: the SKILL.md of an enabled skill, or one of its small support files. */
class SkillViewTool(private val store: SkillsStore) : Tool {
    override val maxResultChars: Int get() = MAX_SKILL_CHARS

    override val spec: ToolSpec = toolSpec(
        name = "skill_view",
        description = "Returns the full instructions (SKILL.md) of an enabled skill. " +
            "Pass file_path to read one of the skill's support files instead (listed in the first result).",
        parameters = objectSchema(
            mapOf(
                "name" to stringProp("Skill name, as returned by skills_list."),
                "file_path" to stringProp("Optional path of a support file inside the skill, e.g. references/notes.md."),
            ),
            required = listOf("name"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val name = args.string("name")?.trim().orEmpty()
        if (name.isEmpty()) throw ToolException("\"name\" is required")
        val skill = store.find(name) ?: run {
            val enabled = store.enabledSkills().map { it.name }
            val available = if (enabled.isEmpty()) "No skills are enabled." else "Enabled skills: ${enabled.joinToString(", ")}."
            throw ToolException("there is no skill called \"$name\". $available")
        }
        if (!store.isEnabled(skill.name)) {
            throw ToolException("the skill \"${skill.name}\" is not enabled. $NOT_ENABLED_HINT")
        }

        val filePath = args.string("file_path")?.trim()?.takeIf { it.isNotEmpty() }
        val files = store.supportFiles(skill)
        if (filePath != null) {
            return store.readSupportFile(skill, filePath) ?: run {
                val list = if (files.isEmpty()) "This skill has no support files." else "Support files: ${files.joinToString(", ")}."
                throw ToolException("file \"$filePath\" was not found in ${skill.name}. $list")
            }
        }

        return buildString {
            append("Skill: ").append(skill.name).append('\n')
            if (skill.description.isNotBlank()) append("Description: ").append(skill.description).append('\n')
            append("Category: ").append(skill.category).append("\n\n")
            append(skill.body.trim())
            if (files.isNotEmpty()) {
                append("\n\nSupport files (read with skill_view and file_path): ").append(files.joinToString(", "))
            }
        }
    }

    private companion object {
        const val MAX_SKILL_CHARS = 24_000
    }
}
