package net.minecraftforge.gradle.common.deobfuscation;

import net.minecraftforge.gradle.common.extensions.DeobfuscationExtension;
import net.minecraftforge.gradle.common.extensions.MappingsExtension;
import net.minecraftforge.gradle.common.extensions.MinecraftArtifactCacheExtension;
import net.minecraftforge.gradle.common.extensions.dependency.replacement.DependencyReplacementContext;
import net.minecraftforge.gradle.common.extensions.dependency.replacement.DependencyReplacementExtension;
import net.minecraftforge.gradle.common.extensions.dependency.replacement.DependencyReplacementHandler;
import net.minecraftforge.gradle.common.extensions.dependency.replacement.DependencyReplacementResult;
import net.minecraftforge.gradle.common.runtime.CommonRuntimeDefinition;
import net.minecraftforge.gradle.common.runtime.extensions.CommonRuntimeExtension;
import net.minecraftforge.gradle.common.runtime.naming.ApplyMappingsTaskBuildingContext;
import net.minecraftforge.gradle.common.runtime.tasks.Execute;
import net.minecraftforge.gradle.common.tasks.ArtifactFromOutput;
import net.minecraftforge.gradle.common.tasks.ITaskWithOutput;
import net.minecraftforge.gradle.common.util.CommonRuntimeUtils;
import net.minecraftforge.gradle.common.util.DecompileUtils;
import net.minecraftforge.gradle.common.util.GameArtifact;
import net.minecraftforge.gradle.common.util.Utils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.LenientConfiguration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public final class DependencyDeobfuscator {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyDeobfuscator.class);
    private static final DependencyDeobfuscator INSTANCE = new DependencyDeobfuscator();

    public static DependencyDeobfuscator getInstance() {
        return INSTANCE;
    }

    private final Collection<DeobfuscatingTaskConfiguration> configurations = new ArrayList<>();

    private DependencyDeobfuscator() {
    }

    public void apply(final Project project) {
        final DependencyReplacementExtension dependencyReplacer = project.getExtensions().getByType(DependencyReplacementExtension.class);
        dependencyReplacer.getReplacementHandlers().add(new DependencyReplacementHandler() {

            @Override
            public @NotNull Optional<DependencyReplacementResult> get(@NotNull DependencyReplacementContext context) {
                if (!(context.dependency() instanceof ExternalModuleDependency)) {
                    return Optional.empty();
                }

                final Configuration resolver = context.project().getConfigurations().detachedConfiguration(context.dependency());
                if (resolver.getResolvedConfiguration().getLenientConfiguration().getFiles().isEmpty()) {
                    return Optional.empty();
                }

                final Set<ResolvedDependency> dependencies = resolver.getResolvedConfiguration().getLenientConfiguration().getFirstLevelModuleDependencies();
                if (dependencies.size() == 0) {
                    return Optional.empty();
                }
                if (dependencies.size() != 1) {
                    LOGGER.warn("Dependency resolution for: " + context.dependency() + " resulted in more then one resolved dependency. Skipping deobfuscation!");
                    return Optional.empty();
                }

                return determineReplacementOptions(context, dependencies.iterator().next());
            }
        });
    }

    private Optional<DependencyReplacementResult> determineReplacementOptions(final DependencyReplacementContext context, final ResolvedDependency resolvedDependency) {
        final Set<ResolvedArtifact> artifacts = resolvedDependency.getModuleArtifacts();
        if (artifacts.size() == 0) {
            return Optional.empty();
        }

        if (artifacts.size() != 1) {
            LOGGER.warn("Dependency resolution for: " + resolvedDependency.getName() + " resulted in more then one file. Can not deobfuscate!");
            return Optional.empty();
        }

        final ResolvedArtifact artifact = artifacts.iterator().next();
        final File file = artifact.getFile();

        try (final JarInputStream jarStream = new JarInputStream(Files.newInputStream(file.toPath()))) {
            Manifest mf = jarStream.getManifest();
            final boolean isObfuscated = mf.getMainAttributes().containsKey("Obfuscated") && Boolean.parseBoolean(mf.getMainAttributes().getValue("Obfuscated"));
            final boolean obfuscatedByForgeGradle = mf.getMainAttributes().containsKey("Obfuscated-By") && mf.getMainAttributes().getValue("Obfuscated-By").equals("ForgeGradle");
            if (isObfuscated && obfuscatedByForgeGradle) {
                final Set<ResolvedDependency> children = resolvedDependency.getChildren();
                final Map<ResolvedDependency, Optional<DependencyReplacementResult>> childResults = children.stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                child -> determineReplacementOptions(context, child)
                        ));

                final Collection<DependencyReplacementResult> dependentResults = childResults.values().stream()
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());

                final TaskProvider<ArtifactFromOutput> rawProvider = context.project().getTasks().register(CommonRuntimeUtils.buildTaskName("deobfuscateRawFrom", resolvedDependency), ArtifactFromOutput.class, task -> {
                    task.getOutputFileName().set(file.getName());
                });

                final TaskProvider<ArtifactFromOutput> sourcesProvider = context.project().getTasks().register(CommonRuntimeUtils.buildTaskName("deobfuscateSourcesFrom", resolvedDependency), ArtifactFromOutput.class, task -> {
                    task.getOutputFileName().set(file.getName().replace(".jar", "-sources.jar"));
                });

                final DependencyReplacementResult result = new DependencyReplacementResult(
                        context.project(),
                        name -> CommonRuntimeUtils.buildTaskName(name, resolvedDependency),
                        sourcesProvider,
                        rawProvider,
                        context.project().getConfigurations().detachedConfiguration(),
                        builder -> {
                            children.forEach(childDependency -> {
                                if (!childResults.containsKey(childDependency) || !childResults.get(childDependency).isPresent()) {
                                    builder.withDependency(depBuilder -> depBuilder.from(childDependency));
                                } else {
                                    final DependencyReplacementResult childResult = childResults.get(childDependency).get();
                                    builder.withDependency(depBuilder -> {
                                        childResult.asDependencyBuilderConfigurator().accept(depBuilder);
                                    });
                                }
                            });
                            builder.from(resolvedDependency);

                            final MappingsExtension mappings = context.project().getExtensions().getByType(MappingsExtension.class);
                            String deobfuscatedMappingsPrefix = mappings.getMappingChannel().get().getDeobfuscationGroupSupplier().get().get();
                            if (deobfuscatedMappingsPrefix == null || deobfuscatedMappingsPrefix.trim().isEmpty()) {
                                deobfuscatedMappingsPrefix = mappings.getMappingChannel().get().getName();
                            }
                            builder.withGroup("fg.deobf." + deobfuscatedMappingsPrefix + "." + resolvedDependency.getModuleGroup());
                        },
                        dependentResults,
                        builder -> {
                            builder.from(resolvedDependency);

                            final MappingsExtension mappings = context.project().getExtensions().getByType(MappingsExtension.class);
                            String deobfuscatedMappingsPrefix = mappings.getMappingChannel().get().getDeobfuscationGroupSupplier().get().get();
                            if (deobfuscatedMappingsPrefix == null || deobfuscatedMappingsPrefix.trim().isEmpty()) {
                                deobfuscatedMappingsPrefix = mappings.getMappingChannel().get().getName();
                            }

                            builder.withGroup("fg.deobf." + deobfuscatedMappingsPrefix + "." + resolvedDependency.getModuleGroup());
                        });

                final DeobfuscatingTaskConfiguration configuration = new DeobfuscatingTaskConfiguration(context, result, resolvedDependency, file);
                this.configurations.add(configuration);

                context.project().afterEvaluate(evaluatedProject -> bakeDependencyReplacement(evaluatedProject, configuration));

                return Optional.of(result);
            } else {
                return Optional.empty();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read manifest for deobfuscation detection!", e);
            return Optional.empty();
        }
    }


    private String createSourcesDependencyIdentifier(final ResolvedArtifact artifact) {
        final StringBuilder builder = new StringBuilder();

        final String group = artifact.getModuleVersion().getId().getGroup();
        final String artifactName = artifact.getModuleVersion().getId().getName();
        final String version = artifact.getModuleVersion().getId().getVersion();
        final String extension = artifact.getExtension();
        final String classifier = artifact.getClassifier();

        if (group != null && !group.trim().isEmpty()) {
            builder.append(group);
        }

        builder.append(":");
        builder.append(artifactName);
        builder.append(":");
        builder.append(version);
        builder.append(":");

        if (classifier != null && !classifier.trim().isEmpty()) {
            builder.append(classifier)
                    .append("-");
        }

        builder.append("sources");

        if (extension != null && !extension.trim().isEmpty() && !extension.trim().toLowerCase(Locale.ROOT).equals("jar")) {
            builder.append("@")
                    .append(extension);
        }

        return builder.toString();
    }

    private Optional<File> getFileFrom(final ResolvedConfiguration resolvedConfiguration) {
        final LenientConfiguration lenientConfiguration = resolvedConfiguration.getLenientConfiguration();
        final Set<ResolvedDependency> resolvedDependencies = lenientConfiguration.getFirstLevelModuleDependencies();
        if (resolvedDependencies.size() != 1)
            return Optional.empty();

        final ResolvedDependency resolvedDependency = resolvedDependencies.iterator().next();
        return getFileFrom(resolvedDependency);
    }

    private Optional<File> getFileFrom(final ResolvedDependency resolvedDependency) {
        final Set<ResolvedArtifact> artifacts = resolvedDependency.getModuleArtifacts();
        if (artifacts.size() != 1)
            return Optional.empty();

        final ResolvedArtifact artifact = artifacts.iterator().next();
        final File artifactFile = artifact.getFile();
        return Optional.of(artifactFile);
    }

    private void bakeDependencyReplacement(final Project project, final DeobfuscatingTaskConfiguration configuration) {
        createRawProvidingTask(project, configuration);
        createSourcesProvidingTask(project, configuration);
    }

    private void createRawProvidingTask(final Project project, final DeobfuscatingTaskConfiguration deobfuscatingTaskConfiguration) {
        final CommonRuntimeExtension<?,?,?> commonRuntimeExtension = project.getExtensions().getByType(CommonRuntimeExtension.class);
        final MappingsExtension mappingsExtension = project.getExtensions().getByType(MappingsExtension.class);

        final File runtimeWorkingDirectory = project.getLayout().getBuildDirectory().dir("dependencies").map(dir -> dir.dir("raw")).get().getAsFile();

        final String postFix = deobfuscatingTaskConfiguration.resolvedDependency().getName();


        final Set<? extends CommonRuntimeDefinition<?>> runtimeDefinitions = commonRuntimeExtension.findIn(deobfuscatingTaskConfiguration.context().configuration());
        CommonRuntimeDefinition<?> runtimeDefinition;
        if (runtimeDefinitions.size() != 1) {
            LOGGER.warn("Found {} runtime definitions for configuration {}!", runtimeDefinitions.size(), deobfuscatingTaskConfiguration.context().configuration());
            LOGGER.warn("Raw jar deobfuscation might not deobfuscate to the correct version!");
        }
        runtimeDefinition = runtimeDefinitions.iterator().next();

        final MinecraftArtifactCacheExtension artifactCache = project.getExtensions().getByType(MinecraftArtifactCacheExtension.class);
        final Map<GameArtifact, TaskProvider<? extends ITaskWithOutput>> gameArtifactTasks = artifactCache.cacheGameVersionTasks(project, new File(runtimeWorkingDirectory, "cache"), runtimeDefinition.spec().minecraftVersion(), runtimeDefinition.spec().side());

        final TaskProvider<? extends ITaskWithOutput> sourceFileProvider = project.getTasks().register(CommonRuntimeUtils.buildTaskName("provide", postFix), ArtifactFromOutput.class, task -> {
            task.getInput().fileValue(getFileFrom(deobfuscatingTaskConfiguration.resolvedDependency()).get());
            task.getOutput().fileValue(new File(runtimeWorkingDirectory,  deobfuscatingTaskConfiguration.resolvedDependency().getName() + "-sources.jar"));
        });

        final TaskProvider<? extends ITaskWithOutput> rawJarDeobfuscator = mappingsExtension.getMappingChannel().get()
                .getApplyCompiledMappingsTaskBuilder().get().build(
                        new ApplyMappingsTaskBuildingContext(
                                project,
                                postFix,
                                sourceFileProvider,
                                runtimeDefinition.configuredMappingVersionData(),
                                gameArtifactTasks)
                );

        deobfuscatingTaskConfiguration.dependencyReplacementResult().rawJarTaskProvider().configure(task -> {
            if (!(task instanceof ArtifactFromOutput)) {
                throw new IllegalStateException("Expected task to be an instance of ArtifactFromOutput!");
            }

            final ArtifactFromOutput artifactFromOutput = (ArtifactFromOutput) task;
            artifactFromOutput.getInput().set(rawJarDeobfuscator.flatMap(ITaskWithOutput::getOutput));
            artifactFromOutput.dependsOn(rawJarDeobfuscator);
        });
    }

    private void createSourcesProvidingTask(final Project project, final DeobfuscatingTaskConfiguration deobfuscatingTaskConfiguration) {
        final Configuration sourcesConfiguration = project.getConfigurations().detachedConfiguration(project.getDependencies().create(this.createSourcesDependencyIdentifier(deobfuscatingTaskConfiguration.resolvedDependency().getModuleArtifacts().iterator().next())));
        final Optional<File> sourcesFileCandidate = getFileFrom(sourcesConfiguration.getResolvedConfiguration());

        final CommonRuntimeExtension<?,?,?> commonRuntimeExtension = project.getExtensions().getByType(CommonRuntimeExtension.class);
        final MappingsExtension mappingsExtension = project.getExtensions().getByType(MappingsExtension.class);

        final File runtimeWorkingDirectory = project.getLayout().getBuildDirectory().dir("dependencies").map(dir -> dir.dir("sources")).get().getAsFile();

        final String postFix = deobfuscatingTaskConfiguration.resolvedDependency().getName() + "Sources";

        TaskProvider<? extends ITaskWithOutput> generateSourcesTask;
        if (sourcesFileCandidate.isPresent()) {
            final Set<? extends CommonRuntimeDefinition<?>> runtimeDefinitions = commonRuntimeExtension.findIn(deobfuscatingTaskConfiguration.context().configuration());
            CommonRuntimeDefinition<?> runtimeDefinition;
            if (runtimeDefinitions.size() != 1) {
                LOGGER.warn("Found {} runtime definitions for configuration {}!", runtimeDefinitions.size(), deobfuscatingTaskConfiguration.context().configuration());
                LOGGER.warn("Source deobfuscation might not deobfuscate to the correct version!");
            }
            runtimeDefinition = runtimeDefinitions.iterator().next();

            final MinecraftArtifactCacheExtension artifactCache = project.getExtensions().getByType(MinecraftArtifactCacheExtension.class);
            final Map<GameArtifact, TaskProvider<? extends ITaskWithOutput>> gameArtifactTasks = artifactCache.cacheGameVersionTasks(project, new File(runtimeWorkingDirectory, "cache"), runtimeDefinition.spec().minecraftVersion(), runtimeDefinition.spec().side());

            final TaskProvider<? extends ITaskWithOutput> sourceFileProvider = project.getTasks().register(CommonRuntimeUtils.buildTaskName("provide", postFix), ArtifactFromOutput.class, task -> {
                task.getInput().fileValue(sourcesFileCandidate.get());
                task.getOutput().fileValue(new File(runtimeWorkingDirectory,  deobfuscatingTaskConfiguration.resolvedDependency().getName() + "-sources.jar"));
            });

            generateSourcesTask = mappingsExtension.getMappingChannel().get()
                    .getApplySourceMappingsTaskBuilder().get().build(
                            new ApplyMappingsTaskBuildingContext(
                                    project,
                                    postFix,
                                    sourceFileProvider,
                                    runtimeDefinition.configuredMappingVersionData(),
                                    gameArtifactTasks)
                    );
        } else {
            LOGGER.warn("Could not find sources for dependency {} decompiling!", deobfuscatingTaskConfiguration.resolvedDependency().getName());

            final DeobfuscationExtension deobfuscationExtension = project.getExtensions().getByType(DeobfuscationExtension.class);

            final TaskProvider<? extends ITaskWithOutput> rawFileProvider = project.getTasks().register(CommonRuntimeUtils.buildTaskName("provide", postFix), ArtifactFromOutput.class, task -> {
                task.getInput().fileValue(getFileFrom(deobfuscatingTaskConfiguration.resolvedDependency()).get());
                task.getOutput().fileValue(new File(runtimeWorkingDirectory,  deobfuscatingTaskConfiguration.resolvedDependency().getName() + "-sources.jar"));
            });

            generateSourcesTask = project.getTasks().register(CommonRuntimeUtils.buildTaskName("decompile", postFix), Execute.class, task -> {
                task.getExecutingArtifact().set(deobfuscationExtension.getForgeFlowerVersion().map(version -> String.format(Utils.FORGEFLOWER_ARTIFACT_INTERPOLATION, version)));
                task.getJvmArguments().addAll(DecompileUtils.DEFAULT_JVM_ARGS);
                task.getProgramArguments().addAll(DecompileUtils.DEFAULT_PROGRAMM_ARGS);
                task.getArguments().set(CommonRuntimeUtils.buildArguments(
                        value -> Optional.empty(),
                        (String value) -> project.provider(() -> value),
                        Collections.emptyMap(),
                        task,
                        Optional.of(rawFileProvider)
                ));
            });
        }

        deobfuscatingTaskConfiguration.dependencyReplacementResult().sourcesJarTaskProvider().configure(task -> {
            if (!(task instanceof ArtifactFromOutput)) {
                throw new IllegalStateException("Expected task to be an instance of ArtifactFromOutput!");
            }

            final ArtifactFromOutput artifactFromOutput = (ArtifactFromOutput) task;
            artifactFromOutput.getInput().set(generateSourcesTask.flatMap(ITaskWithOutput::getOutput));
            artifactFromOutput.dependsOn(generateSourcesTask);
        });
    }
}
