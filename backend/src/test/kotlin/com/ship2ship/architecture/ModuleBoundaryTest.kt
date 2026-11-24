package com.ship2ship.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Module independence: a feature package must not import another feature's code, and core/integrations must not
 * import features. Cross-feature needs go through interfaces in core (e.g. OrganisationLookup, CurrentUserSource).
 */
class ModuleBoundaryTest {

    private val root = File("src/main/kotlin/com/ship2ship")
    private val importPattern = Regex("""^import\s+com\.ship2ship\.features\.(\w+)""", RegexOption.MULTILINE)

    @Test
    fun `features do not import other features`() {
        val violations = root.resolve("features").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val owner = file.relativeTo(root.resolve("features")).path.split(File.separatorChar).first()
            importPattern.findAll(file.readText()).map { it.groupValues[1] }.filter { it != owner }
                .map { "${file.name} imports features.$it" }
        }.toList()
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `core and integrations do not depend on features`() {
        val violations = listOf("core", "integrations", "realtime").map(root::resolve).filter(File::exists)
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { importPattern.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue(violations.isEmpty(), "Depends on features: $violations")
    }

    @Test
    fun `the scan actually found source files`() {
        assertTrue(root.resolve("features").walkTopDown().count { it.extension == "kt" } > 5)
    }
}
