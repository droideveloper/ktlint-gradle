/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jlleitschuh.gradle.ktlint

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Named
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskCollection
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.COLLECT_REPOSITORY_NAME
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.LOCAL_ARTIFACT_KEY
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.LOCAL_PUBLICATION_NAME
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.LOCAL_REPOSITORY_NAME
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.PUBLICATIONS_TO_LOCAL_REPOSITORY
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.SNAPSHOTS_NAME
import org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin.Companion.SNAPSHOT_RELEASE_NAME
import java.io.File
import javax.inject.Inject

public class MavenLocalPublishingPlugin : Plugin<Project> {

    companion object {
        internal const val LOCAL_PUBLICATION_NAME = "localPublication"
        internal const val SNAPSHOTS_NAME = "snapshots"
        internal const val SNAPSHOT_RELEASE_NAME = "snapshotRelease"
        internal const val PUBLICATIONS_TO_LOCAL_REPOSITORY = "publishAllPublicationsToLocalRepository"
        internal const val LOCAL_REPOSITORY_NAME = "local-repository"

        const val COLLECT_REPOSITORY_NAME = "collectRepository"
        internal const val LOCAL_ARTIFACT_KEY = "local.published.gradle-plugin"
        internal const val LOCAL_MAVEN_DIR = ".m2"
        internal const val LOCAL_MAVEN_NAME = "local"

    }

    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply(JavaBasePlugin::class)
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        extensions.configure<JavaPluginExtension> {
            withJavadocJar()
            withSourcesJar()
        }

        val localPublication = extensions.create<LocalPublicationExtension>(LOCAL_PUBLICATION_NAME)
        localPublication.repositoryPath.set(
            layout.buildDirectory.dir(LOCAL_MAVEN_DIR)
        )

        val publication = configurePublicationTask(localPublication)

        val snapshotRelease = configureSnapshotRelease()
        val snapshots = configureSnapshots(localPublication, snapshotRelease)

        createCollectTask {
            dependsOn(publication)
            dependsOn(snapshots)

            local.convention(localPublication.repositoryPath)
            externals.from(snapshots)
        }

        extensions.getByType(PublishingExtension::class).apply {
            repositories.maven {
                setUrl(localPublication.repositoryPath)
                name = LOCAL_MAVEN_NAME
            }

            publications.withType<MavenPublication>().configureEach {
                addMetadata(localPublication)
            }

            tasks.withType<PublishToMavenRepository>().configureEach {
                dependsOn(tasks.withType<Sign>())
            }
        }

        extensions.getByType(SigningExtension::class).apply {
            isRequired = false
        }

        dependencies {}
    }
}

internal fun MavenPublication.addMetadata(
    publication: LocalPublicationExtension,
) {
    pom {
        if (name.isPresent.not()) {
            name.set(publication.metadata.name)
        }
        groupId = publication.metadata.groupId.orNull
        description.set(publication.metadata.description)
        url.set(publication.metadata.url)

        licenses {
            license {
                name.set(publication.license.name)
                url.set(publication.license.url)
                distribution.set(publication.license.distribution)
            }
        }

        developers {
            developer {
                id.set(publication.developer.id)
                name.set(publication.developer.name)
                organization.set(publication.developer.organization)
                organizationUrl.set(publication.developer.organizationUrl)
            }
        }

        scm {
            connection.set(publication.scm.connection)
            developerConnection.set(publication.scm.developerConnection)
            url.set(publication.scm.url)
        }
    }
}

internal fun Project.createCollectTask(
    block: CollectTask.() -> Unit
) {
    tasks.register<CollectTask>(COLLECT_REPOSITORY_NAME) {
        block(this)
    }
}

internal fun Project.configurePublicationTask(
    localPublication: LocalPublicationExtension,
): TaskCollection<*> {
    val publication: TaskCollection<*> = tasks.matching { task -> task.name == PUBLICATIONS_TO_LOCAL_REPOSITORY }
    configurations.register(LOCAL_PUBLICATION_NAME) {
        isCanBeResolved = false
        isCanBeConsumed = true

        attributes.attribute(
            LocalArtifactAttr.of(localPublication.artifactKey),
            objects.named(LOCAL_REPOSITORY_NAME)
        )

        outgoing.artifact(localPublication.repositoryPath) {
            builtBy(publication)
        }
    }
    return publication
}

internal fun Project.configureSnapshotRelease(): Configuration =
    configurations.create(SNAPSHOT_RELEASE_NAME) {
        isCanBeResolved = false
        isCanBeConsumed = false
    }

internal fun Project.configureSnapshots(
    localPublication: LocalPublicationExtension,
    release: Configuration,
): Configuration =
    configurations.create(SNAPSHOTS_NAME) {
        isCanBeResolved = true
        isCanBeConsumed = false

        attributes.attribute(
            LocalArtifactAttr.of(localPublication.artifactKey),
            objects.named(LOCAL_REPOSITORY_NAME)
        )

        extendsFrom(release)
    }

open class PublicationSourceControlManagement @Inject constructor(
    objectFactory: ObjectFactory,
) {
    val connection: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val developerConnection: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val url: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }
}

open class PublicationDeveloper @Inject constructor(
    objectFactory: ObjectFactory,
) {
    val id: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val name: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val organization: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val organizationUrl: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }
}

open class PublicationLicense @Inject constructor(
    objectFactory: ObjectFactory,
) {
    val name: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val url: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val distribution: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }
}

open class PublicationMetadata @Inject constructor(
    objectFactory: ObjectFactory,
) {
    val name: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val groupId: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val description: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }

    val url: Property<String> = objectFactory.property<String>().also {
        it.convention("")
    }
}

open class LocalPublicationExtension @Inject constructor(
    objectFactory: ObjectFactory,
) {
    val artifactKey: Property<String> = objectFactory.property<String>().also {
        it.convention(LOCAL_ARTIFACT_KEY)
    }
    val repositoryPath: DirectoryProperty = objectFactory.directoryProperty()

    val metadata = objectFactory.newInstance<PublicationMetadata>()

    fun metadata(action: Action<PublicationMetadata>) {
        action.execute(metadata)
    }

    val license = objectFactory.newInstance<PublicationLicense>()

    fun license(action: Action<PublicationLicense>) {
        action.execute(license)
    }

    val developer = objectFactory.newInstance<PublicationDeveloper>()

    fun developer(action: Action<PublicationDeveloper>) {
        action.execute(developer)
    }

    val scm = objectFactory.newInstance<PublicationSourceControlManagement>()

    fun scm(action: Action<PublicationSourceControlManagement>) {
        action.execute(scm)
    }
}

internal interface LocalArtifactAttr : Named {
    companion object {
        fun of(key: Property<String>): Attribute<LocalArtifactAttr> = Attribute.of(
            key.getOrElse(LOCAL_ARTIFACT_KEY),
            LocalArtifactAttr::class.java
        )
    }
}

@CacheableTask
abstract class CollectTask: DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val local: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val externals: ConfigurableFileCollection

    @get:OutputDirectories
    val repositories: MutableList<File> = mutableListOf()

    @TaskAction
    fun doCollect() {
        val localFile = local.get().asFile
        repositories += localFile
        repositories += externals.toList()
    }
}
