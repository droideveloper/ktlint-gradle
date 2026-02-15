package org.jlleitschuh.gradle.ktlint

import org.assertj.core.api.Assertions.assertThat
import org.gradle.util.GradleVersion
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask
import org.jlleitschuh.gradle.ktlint.testdsl.CommonTest
import org.jlleitschuh.gradle.ktlint.testdsl.GradleTestVersions
import org.jlleitschuh.gradle.ktlint.testdsl.TestProject.Companion.CLEAN_COMMON_SOURCES_FILE
import org.jlleitschuh.gradle.ktlint.testdsl.build
import org.jlleitschuh.gradle.ktlint.testdsl.project
import org.jlleitschuh.gradle.ktlint.testdsl.projectSetup
import org.junit.jupiter.api.DisplayName
import java.io.File

/**
 * Contains all tests related to "org.jetbrains.kotlin.multiplatform" plugin support.
 */
@GradleTestVersions
class KotlinMultiplatformPluginTests : AbstractPluginTest() {
    private fun multiplatformProjectSetup(): (File) -> Unit = {
        projectSetup("multiplatform").invoke(it)

        //language=Groovy
        it.resolve("build.gradle").appendText(
            """
            |
            |kotlin {
            |    js {
            |      browser()
            |    }
            |    jvm()
            |}
            """.trimMargin()
        )
    }

    @DisplayName("Should NOT add check on sources if they have no source file")
    @CommonTest
    fun addCheckTasks(gradleVersion: GradleVersion) {
        project(gradleVersion, projectSetup = multiplatformProjectSetup()) {
            withCleanSources(filePath = CLEAN_COMMON_SOURCES_FILE)

            build("-m", CHECK_PARENT_TASK_NAME) {
                val ktlintTasks = output.lineSequence().toList()

                assertThat(ktlintTasks).anySatisfy {
                    assertThat(it).contains(
                        GenerateReportsTask.generateNameForSourceSets(
                            "commonMain",
                            GenerateReportsTask.LintType.CHECK
                        )
                    )
                }
                assertThat(ktlintTasks).anySatisfy {
                    assertThat(it).doesNotContain(
                        GenerateReportsTask.generateNameForSourceSets(
                            "JsMain",
                            GenerateReportsTask.LintType.CHECK
                        )
                    )
                }
                assertThat(ktlintTasks).anySatisfy {
                    assertThat(it).doesNotContain(
                        GenerateReportsTask.generateNameForSourceSets(
                            "JvmMain",
                            GenerateReportsTask.LintType.CHECK
                        )
                    )
                }
            }
        }
    }
}
