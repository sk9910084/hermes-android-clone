package com.hermes.clone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** One bundled skill, parsed from a SKILL.md file. */
data class Skill(
    val name: String,
    val description: String,
    val category: String,
    val version: String,
    val author: String,
    val license: String,
    val tags: List<String>,
    /** Asset directory that contains SKILL.md, e.g. "skills/software-development/codebase-inspection". */
    val assetDir: String,
    /** The complete SKILL.md text, including frontmatter and any licence header. */
    val raw: String,
    /** SKILL.md without the frontmatter. */
    val body: String,
)

/**
 * Reads the read-only skills bundled in assets/skills and keeps a per-skill enabled flag.
 * Skills are disabled by default. A skill folder is any directory that holds a SKILL.md.
 */
class SkillsStore(context: Context) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var cache: List<Skill>? = null

    private val _enabled = MutableStateFlow(readEnabled())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    suspend fun all(): List<Skill> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) { scanAll() }.also { cache = it }
    }

    suspend fun find(name: String): Skill? =
        all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    suspend fun enabledSkills(): List<Skill> {
        val on = _enabled.value
        return all().filter { it.name in on }
    }

    fun isEnabled(name: String): Boolean = name in _enabled.value

    fun setEnabled(name: String, enabled: Boolean) {
        synchronized(this) {
            val next = if (enabled) _enabled.value + name else _enabled.value - name
            prefs.edit().putStringSet(KEY_ENABLED, next).apply()
            _enabled.value = next
        }
    }

    fun clearFlags() {
        synchronized(this) {
            prefs.edit().clear().apply()
            _enabled.value = emptySet()
        }
    }

    /** Plain-text files that sit next to SKILL.md (scripts/, references/, templates/). */
    suspend fun supportFiles(skill: Skill): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        fun walk(dir: String, rel: String, depth: Int) {
            val names = safeList(dir)
            for (n in names) {
                if (out.size >= MAX_SUPPORT_FILES) return
                val path = "$dir/$n"
                val relPath = if (rel.isEmpty()) n else "$rel/$n"
                val children = safeList(path)
                if (children.isNotEmpty()) {
                    if (depth < 3) walk(path, relPath, depth + 1)
                } else if (relPath != SKILL_FILE) {
                    out += relPath
                }
            }
        }
        walk(skill.assetDir, "", 0)
        out
    }

    /** Reads one support file of a skill. Returns null when it is missing or the path is unsafe. */
    suspend fun readSupportFile(skill: Skill, relativePath: String): String? = withContext(Dispatchers.IO) {
        val clean = relativePath.trim().trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return@withContext null
        try {
            assets.open("${skill.assetDir}/$clean").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        }
    }

    // ---- scanning -------------------------------------------------------------------------

    private fun scanAll(): List<Skill> {
        val out = mutableListOf<Skill>()
        scan(ROOT, 0, out)
        return out.sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
    }

    private fun scan(dir: String, depth: Int, out: MutableList<Skill>) {
        if (depth > 4) return
        val children = safeList(dir)
        if (SKILL_FILE in children) {
            parseSkill(dir)?.let { out += it }
            return
        }
        for (child in children) {
            val path = "$dir/$child"
            if (safeList(path).isNotEmpty()) scan(path, depth + 1, out)
        }
    }

    private fun safeList(path: String): List<String> =
        try {
            assets.list(path)?.toList().orEmpty()
        } catch (e: IOException) {
            emptyList()
        }

    private fun parseSkill(dir: String): Skill? {
        val raw = try {
            assets.open("$dir/$SKILL_FILE").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return null
        }
        val (front, body) = splitFrontmatter(raw)
        val meta = parseFrontmatter(front)

        val platforms = parseList(meta["platforms"]).map { it.lowercase() }
        // Hide skills that are gated to other operating systems only.
        if (platforms.isNotEmpty() && platforms.none { it == "linux" || it == "android" }) return null

        val folder = dir.substringAfterLast('/')
        val relative = dir.removePrefix(ROOT).trim('/')
        val parentCategory = relative.substringBeforeLast('/', "").substringBefore('/')
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: folder
        return Skill(
            name = name,
            description = meta["description"].orEmpty(),
            category = meta["category"]?.takeIf { it.isNotBlank() } ?: parentCategory.ifBlank { "general" },
            version = meta["version"].orEmpty(),
            author = meta["author"].orEmpty(),
            license = meta["license"].orEmpty(),
            tags = parseList(meta["tags"]),
            assetDir = dir,
            raw = raw,
            body = body,
        )
    }

    /** Finds the first "---" ... "---" block (a licence comment may come before it). */
    private fun splitFrontmatter(raw: String): Pair<List<String>, String> {
        val lines = raw.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trim() == "---" }
        if (start < 0 || start > 20) return emptyList<String>() to lines.joinToString("\n").trim()
        val endRel = lines.drop(start + 1).indexOfFirst { it.trim() == "---" }
        if (endRel < 0) return emptyList<String>() to lines.joinToString("\n").trim()
        val end = start + 1 + endRel
        val front = lines.subList(start + 1, end)
        val body = lines.drop(end + 1).joinToString("\n").trim()
        return front to body
    }

    /**
     * Minimal YAML reader: flattens "key: value" pairs (first occurrence of a key wins),
     * folds ">" / "|" block scalars and turns "- item" lists into "[a, b]".
     */
    private fun parseFrontmatter(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("- ")) continue
            val colon = t.indexOf(':')
            if (colon <= 0) continue
            val indent = line.length - line.trimStart().length
            val key = t.substring(0, colon).trim()
            var value = t.substring(colon + 1).trim()
            if (value == ">" || value == "|" || value == ">-" || value == "|-") {
                val sb = StringBuilder()
                while (i < lines.size && (lines[i].isBlank() || lines[i].length - lines[i].trimStart().length > indent)) {
                    sb.append(lines[i].trim()).append(' ')
                    i++
                }
                value = sb.toString().trim()
            } else if (value.isEmpty()) {
                val items = mutableListOf<String>()
                var j = i
                while (j < lines.size && lines[j].trim().startsWith("- ")) {
                    items += lines[j].trim().removePrefix("- ").trim()
                    j++
                }
                if (items.isNotEmpty()) {
                    value = "[" + items.joinToString(", ") + "]"
                    i = j
                }
            }
            if (key !in out) out[key] = stripQuotes(value)
        }
        return out
    }

    private fun parseList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.trim().removePrefix("[").removeSuffix("]")
            .split(',')
            .map { stripQuotes(it.trim()) }
            .filter { it.isNotEmpty() }
    }

    private fun stripQuotes(s: String): String {
        if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    private fun readEnabled(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()

    private companion object {
        const val ROOT = "skills"
        const val SKILL_FILE = "SKILL.md"
        const val PREFS_NAME = "skills_prefs"
        const val KEY_ENABLED = "enabled_skills"
        const val MAX_SUPPORT_FILES = 40
    }
}
