// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
}

/**
 * Cuts a release by reading the Conventional Commits made since the last tag.
 *
 * feat -> minor, fix/anything else -> patch, "!" or a BREAKING CHANGE footer -> major.
 * While the major version is still 0, a breaking change bumps the minor instead,
 * because 0.y.z is defined as unstable and 0.x -> 1.0.0 should be a deliberate act.
 *
 * Bumps version.properties, regenerates CHANGELOG.md, commits, and tags.
 * Pushing is left to you.
 */
abstract class ReleaseTask : DefaultTask() {

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:Internal
    abstract val versionFile: RegularFileProperty

    @get:Internal
    abstract val changelogFile: RegularFileProperty

    @get:Internal
    abstract val dryRun: Property<Boolean>

    private data class Commit(
        val hash: String,
        val type: String,
        val scope: String?,
        val breaking: Boolean,
        val description: String,
    )

    // git log --format emits these via %x1e / %x1f, so commit text can never collide with them.
    private val recordSeparator = 0x1E.toChar()
    private val fieldSeparator = 0x1F.toChar()

    private val headerPattern = Regex("""^([a-z]+)(?:\(([^)]+)\))?(!)?: (.+)$""")
    private val breakingFooter = Regex("""(?m)^BREAKING[ -]CHANGE:""")
    private val semver = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoRoot.get().asFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            throw GradleException("git ${args.joinToString(" ")} failed:\n$output")
        }
        return output
    }

    private fun gitOrNull(vararg args: String): String? = runCatching { git(*args) }.getOrNull()

    private fun parseCommits(range: String?): List<Commit> {
        val args = mutableListOf("log", "--no-merges", "--format=%h%x1f%s%x1f%b%x1e")
        if (range != null) args.add(range)

        return git(*args.toTypedArray())
            .split(recordSeparator)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { record ->
                val fields = record.split(fieldSeparator)
                if (fields.size < 2) return@mapNotNull null
                val hash = fields[0].trim()
                val subject = fields[1].trim()
                val body = fields.getOrElse(2) { "" }

                val match = headerPattern.find(subject) ?: return@mapNotNull null
                val (type, scope, bang, description) = match.destructured
                Commit(
                    hash = hash,
                    type = type,
                    scope = scope.takeIf(String::isNotEmpty),
                    breaking = bang == "!" || breakingFooter.containsMatchIn(body),
                    description = description,
                )
            }
    }

    private fun bump(current: String, commits: List<Commit>): Pair<String, String> {
        val match = semver.find(current)
            ?: throw GradleException("versionName '$current' is not a semantic version (x.y.z)")
        var (major, minor, patch) = match.destructured.toList().map(String::toInt)

        val breaking = commits.any { it.breaking }
        val hasFeature = commits.any { it.type == "feat" }

        return when {
            breaking && major > 0 -> {
                major++; minor = 0; patch = 0
                "$major.$minor.$patch" to "major"
            }
            breaking -> {
                // 0.y.z is unstable; a break bumps the minor, not to 1.0.0.
                minor++; patch = 0
                "$major.$minor.$patch" to "minor (breaking, but still on 0.x)"
            }
            hasFeature -> {
                minor++; patch = 0
                "$major.$minor.$patch" to "minor"
            }
            else -> {
                patch++
                "$major.$minor.$patch" to "patch"
            }
        }
    }

    private fun changelogEntry(version: String, commits: List<Commit>): String {
        val sections = linkedMapOf(
            "feat" to "Features",
            "fix" to "Bug Fixes",
            "perf" to "Performance",
            "refactor" to "Refactoring",
            "docs" to "Documentation",
            "build" to "Build System",
            "ci" to "Continuous Integration",
            "revert" to "Reverts",
        )

        return buildString {
            appendLine("## [$version] - ${LocalDate.now()}")
            appendLine()

            val breaking = commits.filter { it.breaking }
            if (breaking.isNotEmpty()) {
                appendLine("### ⚠ BREAKING CHANGES")
                appendLine()
                breaking.forEach { appendLine("- ${it.render()}") }
                appendLine()
            }

            sections.forEach { (type, heading) ->
                val matching = commits.filter { it.type == type }
                if (matching.isNotEmpty()) {
                    appendLine("### $heading")
                    appendLine()
                    matching.forEach { appendLine("- ${it.render()}") }
                    appendLine()
                }
            }
        }
    }

    private fun Commit.render(): String {
        val prefix = scope?.let { "**$it:** " } ?: ""
        return "$prefix$description ($hash)"
    }

    private fun writeVersion(file: File, versionName: String, versionCode: Int) {
        // Rewritten by hand rather than via Properties.store() to keep the header comment.
        file.writeText(
            """
            # Single source of truth for the app version.
            # Managed by `./gradlew release` — bump it there, not by hand.
            #
            # versionName  semantic version shown to users
            # versionCode  monotonically increasing integer required by the Play Store
            versionName=$versionName
            versionCode=$versionCode
            """.trimIndent() + "\n"
        )
    }

    @TaskAction
    fun release() {
        val isDryRun = dryRun.get()

        val dirty = git("status", "--porcelain", "--untracked-files=no")
        if (dirty.isNotEmpty()) {
            throw GradleException("Working tree has uncommitted changes:\n$dirty")
        }

        val versions = versionFile.get().asFile
        val properties = Properties().apply { versions.inputStream().use(::load) }
        val currentName = properties.getProperty("versionName")
            ?: throw GradleException("versionName missing from ${versions.name}")
        val currentCode = properties.getProperty("versionCode")?.toIntOrNull()
            ?: throw GradleException("versionCode missing or non-numeric in ${versions.name}")

        val lastTag = gitOrNull("describe", "--tags", "--abbrev=0", "--match=v*")
        val commits = parseCommits(lastTag?.let { "$it..HEAD" })

        if (commits.isEmpty()) {
            throw GradleException(
                "No conventional commits since ${lastTag ?: "the first commit"} — nothing to release."
            )
        }

        val (nextName, reason) = bump(currentName, commits)
        val nextCode = currentCode + 1
        val tag = "v$nextName"

        if (gitOrNull("rev-parse", "--verify", "refs/tags/$tag") != null) {
            throw GradleException("Tag $tag already exists.")
        }

        val tally = commits.groupingBy { it.type }.eachCount()
            .entries.sortedBy { it.key }
            .joinToString(", ") { "${it.value} ${it.key}" }

        logger.lifecycle("  Last tag:    ${lastTag ?: "(none)"}")
        logger.lifecycle("  Commits:     $tally")
        logger.lifecycle("  Bump:        $reason -> $nextName (versionCode $nextCode)")

        if (isDryRun) {
            logger.lifecycle("")
            logger.lifecycle("  Dry run — nothing written. Changelog entry would be:")
            logger.lifecycle("")
            changelogEntry(nextName, commits).lines().forEach { logger.lifecycle("    $it") }
            return
        }

        writeVersion(versions, nextName, nextCode)

        val changelog = changelogFile.get().asFile
        val preamble = """
            # Changelog

            All notable changes to this project are documented here.
            Generated by `./gradlew release` from [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).
        """.trimIndent()

        // Everything from the first release heading onward; the preamble is always regenerated.
        val existing = changelog.takeIf(File::exists)
            ?.readText()
            ?.let { text -> text.indexOf("## [").takeIf { it >= 0 }?.let(text::substring) }
            .orEmpty()

        changelog.writeText("$preamble\n\n${changelogEntry(nextName, commits)}$existing".trimEnd() + "\n")
        logger.lifecycle("  Changelog:   ${changelog.name} updated")

        git("add", "--", versions.absolutePath, changelog.absolutePath)
        git("commit", "-m", "chore(release): $tag")
        logger.lifecycle("  Committed:   chore(release): $tag")

        git("tag", "-a", tag, "-m", "Release $tag")
        logger.lifecycle("  Tagged:      $tag")

        val branch = git("rev-parse", "--abbrev-ref", "HEAD")
        logger.lifecycle("")
        logger.lifecycle("  Push with: git push --follow-tags origin $branch")
    }
}

tasks.register<ReleaseTask>("release") {
    group = "release"
    description = "Bumps the app version from Conventional Commits, updates the changelog, commits, and tags."
    repoRoot.set(layout.projectDirectory)
    versionFile.set(layout.projectDirectory.file("version.properties"))
    changelogFile.set(layout.projectDirectory.file("CHANGELOG.md"))
    dryRun.set(providers.gradleProperty("dryRun").map { true }.orElse(false))
    outputs.upToDateWhen { false }
}
