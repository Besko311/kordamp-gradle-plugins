/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2018-2019 Andres Almiray.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kordamp.gradle.plugin.scaladoc

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.BuildAdapter
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.scala.ScalaDoc
import org.kordamp.gradle.plugin.AbstractKordampPlugin
import org.kordamp.gradle.plugin.base.BasePlugin
import org.kordamp.gradle.plugin.base.ProjectConfigurationExtension
import org.kordamp.gradle.plugin.javadoc.JavadocPlugin

import static org.kordamp.gradle.PluginUtils.resolveEffectiveConfig
import static org.kordamp.gradle.plugin.base.BasePlugin.isRootProject

/**
 * Configures {@code scaladoc} and {@code scaladocJar} tasks.
 *
 * @author Andres Almiray
 * @since 0.15.0
 */
@CompileStatic
class ScaladocPlugin extends AbstractKordampPlugin {
    static final String SCALADOC_TASK_NAME = 'scaladoc'
    static final String SCALADOC_JAR_TASK_NAME = 'scaladocJar'
    static final String AGGREGATE_SCALADOCS_TASK_NAME = 'aggregateScaladocs'
    static final String AGGREGATE_SCALADOCS_JAR_TASK_NAME = 'aggregateScaladocsJar'

    Project project

    void apply(Project project) {
        this.project = project

        if (isRootProject(project)) {
            if (project.childProjects.size()) {
                project.childProjects.values().each {
                    configureProject(it)
                }
                configureRootProject(project, true)
            } else {
                configureProject(project)
                configureRootProject(project, false)
            }
        } else {
            configureProject(project)
        }
    }

    static void applyIfMissing(Project project) {
        if (!project.plugins.findPlugin(ScaladocPlugin)) {
            project.pluginManager.apply(ScaladocPlugin)
        }
    }

    private void configureRootProject(Project project, boolean checkIfApplied) {
        if (checkIfApplied && hasBeenVisited(project)) {
            return
        }
        setVisited(project, true)

        BasePlugin.applyIfMissing(project)

        if (isRootProject(project)) {
            createAggregateScaladocsTask(project)

            project.gradle.addBuildListener(new BuildAdapter() {
                @Override
                void projectsEvaluated(Gradle gradle) {
                    doConfigureRootProject(project)
                }
            })
        }
    }

    @CompileDynamic
    private void doConfigureRootProject(Project project) {
        ProjectConfigurationExtension effectiveConfig = resolveEffectiveConfig(project)
        setEnabled(effectiveConfig.docs.scaladoc.enabled)

        if (!enabled) {
            return
        }

        if (!project.childProjects.isEmpty()) {
            List<ScalaDoc> scaladocs = []
            project.tasks.withType(ScalaDoc) { ScalaDoc scaladoc -> if (scaladoc.name != AGGREGATE_SCALADOCS_TASK_NAME && scaladoc.enabled) scaladocs << scaladoc }
            project.childProjects.values().each { Project p ->
                if (p in effectiveConfig.docs.scaladoc.excludedProjects()) return
                p.tasks.withType(ScalaDoc) { ScalaDoc scaladoc -> if (scaladoc.enabled) scaladocs << scaladoc }
            }
            scaladocs = scaladocs.unique()

            ScalaDoc aggregateScaladocs = (ScalaDoc) project.tasks.findByName(AGGREGATE_SCALADOCS_TASK_NAME)
            Jar aggregateScaladocsJar = (Jar) project.tasks.findByName(AGGREGATE_SCALADOCS_JAR_TASK_NAME)

            if (scaladocs) {
                aggregateScaladocs.configure { ScalaDoc task ->
                    task.enabled = true
                    task.dependsOn scaladocs
                    task.source scaladocs.source
                    task.classpath = project.files(scaladocs.classpath)

                    effectiveConfig.docs.scaladoc.applyTo(task)
                    // task.options.footer = "Copyright &copy; ${effectiveConfig.info.copyrightYear} ${effectiveConfig.info.authors.join(', ')}. All rights reserved."
                }
                aggregateScaladocsJar.configure {
                    enabled true
                    from aggregateScaladocs.destinationDir
                    classifier = effectiveConfig.docs.scaladoc.replaceJavadoc ? 'javadoc' : 'scaladoc'
                }
            }
        }
    }

    private void configureProject(Project project) {
        if (hasBeenVisited(project)) {
            return
        }
        setVisited(project, true)

        BasePlugin.applyIfMissing(project)

        project.pluginManager.withPlugin('scala-base') {
            project.afterEvaluate {
                ProjectConfigurationExtension effectiveConfig = resolveEffectiveConfig(project)
                setEnabled(effectiveConfig.docs.scaladoc.enabled)

                if (!enabled) {
                    return
                }

                ScalaDoc scaladoc = configureScaladocTask(project)
                effectiveConfig.docs.scaladoc.scaladocTasks() << scaladoc

                TaskProvider<Jar> scaladocJar = createScaladocJarTask(project, scaladoc)
                project.tasks.findByName(org.gradle.api.plugins.BasePlugin.ASSEMBLE_TASK_NAME).dependsOn(scaladocJar)
                effectiveConfig.docs.scaladoc.scaladocJarTasks() << scaladocJar

                effectiveConfig.docs.scaladoc.projects() << project

                project.tasks.withType(ScalaDoc) { ScalaDoc task ->
                    effectiveConfig.docs.scaladoc.applyTo(task)
                    // task.scalaDocOptions.footer = "Copyright &copy; ${effectiveConfig.info.copyrightYear} ${effectiveConfig.info.getAuthors().join(', ')}. All rights reserved."
                }
            }
        }
    }

    @CompileDynamic
    private ScalaDoc configureScaladocTask(Project project) {
        String taskName = SCALADOC_TASK_NAME

        ScalaDoc scaladocTask = project.tasks.findByName(taskName)
        Task classesTask = project.tasks.findByName('classes')

        if (classesTask && !scaladocTask) {
            scaladocTask = project.tasks.create(taskName, ScalaDoc) {
                dependsOn classesTask
                group = JavaBasePlugin.DOCUMENTATION_GROUP
                description = 'Generates Scaladoc API documentation'
                source project.sourceSets.main.allSource
                destinationDir = project.file("${project.buildDir}/docs/scaladoc")
            }
        }

        ProjectConfigurationExtension effectiveConfig = resolveEffectiveConfig(project)
        scaladocTask.configure {
            include(effectiveConfig.docs.scaladoc.includes)
            exclude(effectiveConfig.docs.scaladoc.excludes)
        }

        scaladocTask
    }

    private TaskProvider<Jar> createScaladocJarTask(Project project, ScalaDoc scaladoc) {
        String taskName = SCALADOC_JAR_TASK_NAME

        TaskProvider<Jar> scaladocJarTask = project.tasks.register(taskName, Jar,
                new Action<Jar>() {
                    @Override
                    void execute(Jar t) {
                        t.dependsOn scaladoc
                        t.group = JavaBasePlugin.DOCUMENTATION_GROUP
                        t.description = 'An archive of the Scaladoc API docs'
                        t.archiveClassifier.set('scaladoc')
                        t.from scaladoc.destinationDir
                    }
                })

        ProjectConfigurationExtension effectiveConfig = resolveEffectiveConfig(project)
        if (effectiveConfig.docs.scaladoc.replaceJavadoc) {
            scaladocJarTask.configure(new Action<Jar>() {
                @Override
                void execute(Jar t) {
                    t.archiveClassifier.set('javadoc')
                }
            })
            project.tasks.findByName(JavadocPlugin.JAVADOC_TASK_NAME)?.enabled = false
            project.tasks.findByName(JavadocPlugin.JAVADOC_JAR_TASK_NAME)?.enabled = false
        }

        if (project.pluginManager.hasPlugin('maven-publish')) {
            PublishingExtension publishing = project.extensions.findByType(PublishingExtension)
            MavenPublication mainPublication = (MavenPublication) publishing.publications.findByName('main')
            if (effectiveConfig.docs.scaladoc.replaceJavadoc) {
                MavenArtifact javadocJar = mainPublication.artifacts.find { it.classifier == 'javadoc' }
                mainPublication.artifacts.remove(javadocJar)
            }
            mainPublication.artifact(scaladocJarTask.get())
        }

        scaladocJarTask
    }

    private void createAggregateScaladocsTask(Project project) {
        TaskProvider<ScalaDoc> aggregateScaladocs = project.tasks.register(AGGREGATE_SCALADOCS_TASK_NAME, ScalaDoc,
                new Action<ScalaDoc>() {
                    @Override
                    void execute(ScalaDoc t) {
                        t.enabled = false
                        t.group = JavaBasePlugin.DOCUMENTATION_GROUP
                        t.description = 'Aggregates Scaladoc API docs for all projects.'
                        t.destinationDir = project.file("${project.buildDir}/docs/scaladoc")
                    }
                })

        project.tasks.register(AGGREGATE_SCALADOCS_JAR_TASK_NAME, Jar,
                new Action<Jar>() {
                    @Override
                    void execute(Jar t) {
                        t.dependsOn aggregateScaladocs
                        t.enabled = false
                        t.group = JavaBasePlugin.DOCUMENTATION_GROUP
                        t.description = 'An archive of the aggregate Scaladoc API docs'
                        t.archiveClassifier.set('scaladoc')
                    }
                })
    }
}
