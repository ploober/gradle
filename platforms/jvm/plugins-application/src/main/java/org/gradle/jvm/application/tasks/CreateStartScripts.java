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

package org.gradle.jvm.application.tasks;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Incubating;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.internal.plugins.AppEntryPoint;
import org.gradle.api.internal.plugins.MainClass;
import org.gradle.api.internal.plugins.MainModule;
import org.gradle.api.internal.plugins.StartScriptGenerator;
import org.gradle.api.internal.plugins.UnixStartScriptGenerator;
import org.gradle.api.internal.plugins.WindowsStartScriptGenerator;
import org.gradle.api.jvm.ModularitySpec;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.jvm.DefaultModularitySpec;
import org.gradle.internal.jvm.JavaModuleDetector;
import org.gradle.jvm.application.scripts.JavaAppStartScriptGenerationDetails;
import org.gradle.jvm.application.scripts.ScriptGenerator;
import org.gradle.jvm.application.scripts.TemplateBasedScriptGenerator;
import org.gradle.util.internal.GUtil;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.util.stream.Collectors;

/**
 * Creates start scripts for launching JVM applications.
 * <p>
 * Example:
 * <pre class='autoTested'>
 * task createStartScripts(type: CreateStartScripts) {
 *   outputDir = file('build/sample')
 *   mainClass = 'org.gradle.test.Main'
 *   applicationName = 'myApp'
 *   classpath = files('path/to/some.jar')
 * }
 * </pre>
 * <p>
 * Note: the Gradle {@code "application"} plugin adds a pre-configured task of this type named {@code "startScripts"}.
 * <p>
 * The task generates separate scripts targeted at Microsoft Windows environments and UNIX-like environments (e.g. Linux, macOS).
 * The actual generation is implemented by the {@link #getWindowsStartScriptGenerator()} and {@link #getUnixStartScriptGenerator()} properties, of type {@link ScriptGenerator}.
 * <p>
 * Example:
 * <pre class='autoTested'>
 * task createStartScripts(type: CreateStartScripts) {
 *   unixStartScriptGenerator = new CustomUnixStartScriptGenerator()
 *   windowsStartScriptGenerator = new CustomWindowsStartScriptGenerator()
 * }
 *
 * class CustomUnixStartScriptGenerator implements ScriptGenerator {
 *   void generateScript(JavaAppStartScriptGenerationDetails details, Writer destination) {
 *     // implementation
 *   }
 * }
 *
 * class CustomWindowsStartScriptGenerator implements ScriptGenerator {
 *   void generateScript(JavaAppStartScriptGenerationDetails details, Writer destination) {
 *     // implementation
 *   }
 * }
 * </pre>
 * <p>
 * The default generators are of the type {@link TemplateBasedScriptGenerator}, with default templates.
 * The templates can be changed via the {@link TemplateBasedScriptGenerator#setTemplate(TextResource)} method.
 * <p>
 * The default implementations used by this task use <a href="https://docs.groovy-lang.org/latest/html/documentation/template-engines.html#_simpletemplateengine">Groovy's SimpleTemplateEngine</a>
 * to parse the template, with the following variables available:
 * <ul>
 * <li>{@code applicationName} - See {@link JavaAppStartScriptGenerationDetails#getApplicationName()}.</li>
 * <li>{@code gitRef} - See {@link JavaAppStartScriptGenerationDetails#getGitRef()}.</li>
 * <li>{@code optsEnvironmentVar} - See {@link JavaAppStartScriptGenerationDetails#getOptsEnvironmentVar()}.</li>
 * <li>{@code exitEnvironmentVar} - See {@link JavaAppStartScriptGenerationDetails#getExitEnvironmentVar()}.</li>
 * <li>{@code moduleEntryPoint} - The module entry point, or {@code null} if none. Will also include the main class name if present, in the form {@code [moduleName]/[className]}.</li>
 * <li>{@code mainClassName} - The main class name, or usually {@code ""} if none. For legacy reasons, this may be set to {@code --module [moduleEntryPoint]} when using a main module.
 * This behavior should not be relied upon and may be removed in a future release.</li>
 * <li>{@code entryPointArgs} - The arguments to be used on the command-line to enter the application, as a joined string. It should be inserted before the program arguments.</li>
 * <li>{@code defaultJvmOpts} - See {@link JavaAppStartScriptGenerationDetails#getDefaultJvmOpts()}.</li>
 * <li>{@code appNameSystemProperty} - See {@link JavaAppStartScriptGenerationDetails#getAppNameSystemProperty()}.</li>
 * <li>{@code appHomeRelativePath} - The path, relative to the script's own path, of the app home.</li>
 * <li>{@code classpath} - See {@link JavaAppStartScriptGenerationDetails#getClasspath()}. It is already encoded as a joined string.</li>
 * <li>{@code modulePath} (different capitalization) - See {@link JavaAppStartScriptGenerationDetails#getModulePath()}. It is already encoded as a joined string.</li>
 * </ul>
 * <p>
 * The encoded paths expect a variable named {@code APP_HOME} to be present in the script, set to the application home directory which can be resolved using {@code appHomeRelativePath}.
 * </p>
 * <p>
 * Example:
 * <pre>
 * task createStartScripts(type: CreateStartScripts) {
 *   unixStartScriptGenerator.template = resources.text.fromFile('customUnixStartScript.txt')
 *   windowsStartScriptGenerator.template = resources.text.fromFile('customWindowsStartScript.txt')
 * }
 * </pre>
 */
@DisableCachingByDefault(because = "Not worth caching")
public abstract class CreateStartScripts extends DefaultTask {

    private final ModularitySpec modularity;
    private ScriptGenerator unixStartScriptGenerator = new UnixStartScriptGenerator();
    private ScriptGenerator windowsStartScriptGenerator = new WindowsStartScriptGenerator();

    public CreateStartScripts() {
        this.modularity = getObjectFactory().newInstance(DefaultModularitySpec.class);
        getExecutableDir().convention("bin");
        getGitRef().convention("HEAD");
        getOptsEnvironmentVar().convention(getApplicationName().map(n -> GUtil.toConstant(n) + "_OPTS"));
        getExitEnvironmentVar().convention(getApplicationName().map(n -> GUtil.toConstant(n) + "_EXIT_CONSOLE"));
        getUnixScript().convention(getOutputDir().file(getApplicationName()));
        getWindowsScript().convention(getOutputDir().file(getApplicationName().map(n -> n + ".bat")));
        getRelativeClasspath().convention(getProject().provider(() -> getRelativePath(getClasspath()))); // TODO - is this right?
    }

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Inject
    protected abstract JavaModuleDetector getJavaModuleDetector();

    /**
     * The directory to write the scripts into.
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    /**
     * The directory to write the scripts into in the distribution.
     *
     * @since 4.5
     */
    @Input
    public abstract Property<String> getExecutableDir();

    /**
     * The main module name used to start the modular Java application.
     *
     * @since 6.4
     */
    @Optional
    @Input
    public abstract Property<String> getMainModule();

    /**
     * The main class name used to start the Java application.
     *
     * @since 6.4
     */
    @Optional
    @Input
    public abstract Property<String> getMainClass();

    /**
     * The application's default JVM options. Defaults to an empty list.
     */
    @Optional
    @Input
    public abstract ListProperty<String> getDefaultJvmOpts();

    /**
     * The application's name.
     */
    @Optional
    @Input
    public abstract Property<String> getApplicationName();

    /**
     * The Git revision or tag.
     *
     * @since 9.4.0
     */
    @Incubating
    @Optional
    @Input
    public abstract Property<String> getGitRef();

    /**
     * The environment variable to use to provide additional options to the JVM.
     */
    @Optional
    @Input
    public abstract Property<String> getOptsEnvironmentVar();

    /**
     * The environment variable to use to control exit value (Windows only).
     */
    @Optional
    @Input
    public abstract Property<String> getExitEnvironmentVar();

    /**
     * The class path for the application.
     */
    @Optional
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    /**
     * Returns the module path handling for executing the main class.
     *
     * @since 6.4
     */
    @Nested
    public ModularitySpec getModularity() {
        return modularity;
    }

    /**
     * The UNIX-like start script.
     *
     * @since 7.0
     */
    @Internal
    public abstract RegularFileProperty getUnixScript();

    /**
     * The Windows start script.
     *
     * @since 7.0
     */
    @Internal
    public abstract RegularFileProperty getWindowsScript();

    /**
     * The UNIX-like start script generator.
     * <p>
     * Defaults to an implementation of {@link TemplateBasedScriptGenerator}.
     */
    @Nested
    public ScriptGenerator getUnixStartScriptGenerator() {
        return unixStartScriptGenerator;
    }

    public void setUnixStartScriptGenerator(ScriptGenerator unixStartScriptGenerator) {
        this.unixStartScriptGenerator = unixStartScriptGenerator;
    }

    /**
     * The Windows start script generator.
     * <p>
     * Defaults to an implementation of {@link TemplateBasedScriptGenerator}.
     */
    @Nested
    public ScriptGenerator getWindowsStartScriptGenerator() {
        return windowsStartScriptGenerator;
    }

    public void setWindowsStartScriptGenerator(ScriptGenerator windowsStartScriptGenerator) {
        this.windowsStartScriptGenerator = windowsStartScriptGenerator;
    }

    @TaskAction
    public void generate() {
        StartScriptGenerator generator = new StartScriptGenerator(unixStartScriptGenerator, windowsStartScriptGenerator);
        JavaModuleDetector javaModuleDetector = getJavaModuleDetector();
        generator.setApplicationName(getApplicationName().get());
        generator.setGitRef(getGitRef().get());
        generator.setEntryPoint(getEntryPoint());
        generator.setDefaultJvmOpts(getDefaultJvmOpts().get());
        generator.setOptsEnvironmentVar(getOptsEnvironmentVar().get());
        generator.setExitEnvironmentVar(getExitEnvironmentVar().get());
        generator.setClasspath(getRelativeClasspath().get());
        generator.setModulePath(getRelativePath(javaModuleDetector.inferModulePath(getMainModule().isPresent(), getClasspath())));
        if (StringUtils.isEmpty(getExecutableDir().get())) {
            generator.setScriptRelPath(getUnixScript().get().getAsFile().getName());
        } else {
            generator.setScriptRelPath(getExecutableDir().get() + "/" + getUnixScript().get().getAsFile().getName());
        }
        generator.generateUnixScript(getUnixScript().get().getAsFile());
        generator.generateWindowsScript(getWindowsScript().get().getAsFile());
    }

    private AppEntryPoint getEntryPoint() {
        if (getMainModule().isPresent()) {
            return new MainModule(getMainModule().get(), getMainClass().getOrNull());
        }
        return new MainClass(getMainClass().getOrElse(""));
    }

    @Input
    protected abstract ListProperty<String> getRelativeClasspath();

    private Iterable<String> getRelativePath(FileCollection path) {
        return path.getFiles().stream().map(input -> "lib/" + input.getName()).collect(Collectors.toCollection(Lists::newArrayList));
    }
}
