/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.internal.component.local.model;

import com.google.common.collect.ListMultimap;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.internal.component.external.descriptor.Artifact;
import org.gradle.internal.component.model.DefaultDependencyMetadata;
import org.gradle.internal.component.model.Exclude;

import java.util.List;

public class DefaultProjectDependencyMetadata extends DefaultDependencyMetadata {
    private final String projectPath;

    public DefaultProjectDependencyMetadata(String projectPath, ModuleVersionSelector requested, ListMultimap<String, String> confs,
                                            List<Artifact> dependencyArtifacts, List<Exclude> excludeRules,
                                            String dynamicConstraintVersion, boolean changing, boolean transitive) {
        super(requested, confs, dependencyArtifacts, excludeRules, dynamicConstraintVersion, changing, transitive);
        this.projectPath = projectPath;
    }

    @Override
    public ProjectComponentSelector getSelector() {
        return new DefaultProjectComponentSelector(projectPath);
    }
}
