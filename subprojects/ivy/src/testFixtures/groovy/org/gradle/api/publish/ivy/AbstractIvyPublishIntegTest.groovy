/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.api.publish.ivy

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.ExperimentalFeaturesFixture
import org.gradle.test.fixtures.GradleMetadataAwarePublishingSpec
import org.gradle.test.fixtures.ivy.IvyFileModule
import org.gradle.test.fixtures.ivy.IvyJavaModule
import org.gradle.test.fixtures.ivy.IvyModule

import static org.gradle.integtests.fixtures.RepoScriptBlockUtil.mavenCentralRepositoryDefinition

class AbstractIvyPublishIntegTest extends AbstractIntegrationSpec implements GradleMetadataAwarePublishingSpec {

    def setup() {
        prepare()
    }

    ResolveParams resolveParams(Map args = [:]) {
        new ResolveParams(args)
    }

    protected static IvyJavaModule javaLibrary(IvyFileModule module) {
        new IvyJavaModule(module)
    }

    protected def resolveArtifacts(IvyModule module) {
        doResolveArtifacts("group: '${sq(module.organisation)}', name: '${sq(module.module)}', version: '${sq(module.revision)}'")
    }

    protected def resolveArtifacts(IvyModule module, String configuration) {
        doResolveArtifacts("group: '${sq(module.organisation)}', name: '${sq(module.module)}', version: '${sq(module.revision)}', configuration: '${sq(configuration)}'")
    }

    protected def resolveAdditionalArtifacts(IvyJavaModule module) {
        doResolveArtifacts("group: '${sq(module.organisation)}', name: '${sq(module.module)}', version: '${sq(module.revision)}'", resolveParams(additionalArtifacts: module.additionalArtifacts))
    }

    protected def resolveArtifactsWithStatus(IvyModule module, String status) {
        doResolveArtifacts("group: '${sq(module.organisation)}', name: '${sq(module.module)}', version: '${sq(module.revision)}'", resolveParams(status:status))
    }

    private def doResolveArtifacts(String dependency, ResolveParams params = resolveParams()) {
        // Replace the existing buildfile with one for resolving the published module
        settingsFile.text = "rootProject.name = 'resolve'"

        if (resolveModuleMetadata) {
            ExperimentalFeaturesFixture.enable(settingsFile)
        } else {
            executer.beforeExecute {
                // Remove the experimental flag set earlier...
                // TODO:DAZ Remove this once we support excludes and we can have a single flag to enable publish/resolve
                withArguments()
            }
        }

        String attributes = params.variant == null ?
            "" :
            """ 
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.${params.variant}))
    }
"""

        String extraArtifacts = ""
        if (params.additionalArtifacts) {
            String artifacts = params.additionalArtifacts.collect {
                    def tokens = it.ivyTokens
                    """
                    artifact {
                        name = '${sq(tokens.artifact)}'
                        classifier = '${sq(tokens.classifier)}'
                        type = '${sq(tokens.type)}'
                    }"""
            }.join('\n')
            extraArtifacts = """
                {
                    transitive = false
                    $artifacts
                }
            """
        }

        buildFile.text = """
            configurations {
                resolve {
                    $attributes
                }
            }
            repositories {
                ivy { url "${ivyRepo.uri}" }
                ${mavenCentralRepositoryDefinition()}
            }
            dependencies {
                resolve($dependency) $extraArtifacts
            }

            task resolveArtifacts(type: Sync) {
                from configurations.resolve
                into "artifacts"
            }
        """

        if (params.status != null) {
            buildFile.text = buildFile.text + """

                dependencies.components.all { ComponentMetadataDetails details, IvyModuleDescriptor ivyModule ->
                    details.statusScheme = [ '${sq(params.status)}' ]
                }
            """
        }

        run "resolveArtifacts"
        def artifactsList = file("artifacts").exists() ? file("artifacts").list() : []
        return artifactsList.sort()
    }

    String sq(String input) {
        return escapeForSingleQuoting(input)
    }

    String escapeForSingleQuoting(String input) {
        return input.replace('\\', '\\\\').replace('\'', '\\\'')
    }

    static class ResolveParams {
        List<IvyFileModule.IvyModuleArtifact> additionalArtifacts
        String status
        String variant
    }
}
