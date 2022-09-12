package net.minecraftforge.gradle.mcp.runtime.tasks;

import net.minecraftforge.gradle.common.util.FileWrapper;
import net.minecraftforge.gradle.common.util.TransformerUtils;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CacheableTask
public abstract class ExecutingMcpRuntimeTask extends McpRuntimeTask {

    private static final Pattern REPLACE_PATTERN = Pattern.compile("^\\{(\\w+)}$");

    public ExecutingMcpRuntimeTask() {
        super();

        getLogFileName().convention(getArguments().map(arguments -> (String) arguments.getOrDefault("log", "log.log")));
        getLogFile().convention(getOutputDirectory().flatMap(d -> getLogFileName().map(d::file)));

        getConsoleLogFileName().convention(getArguments().map(arguments -> (String) arguments.getOrDefault("console.log", "console.log")));
        getConsoleLogFile().convention(getOutputDirectory().flatMap(d -> getConsoleLogFileName().map(d::file)));

        getMainClass().convention(getExecutingJar().map(TransformerUtils.guardWithResource(
                jarFile -> jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS),
                f -> new JarFile(f.getAsFile())
        )));

        getExecutingJar().fileProvider(getExecutingArtifact().flatMap(artifact -> getDownloader().flatMap(downloader -> downloader.gradle(artifact, false))));

        getConsoleLogFileName().finalizeValueOnRead();
        getConsoleLogFile().finalizeValueOnRead();
        getLogFileName().finalizeValueOnRead();
        getLogFile().finalizeValueOnRead();
        getJvmArguments().finalizeValueOnRead();
        getProgramArguments().finalizeValueOnRead();
        getExecutingArtifact().finalizeValueOnRead();
        getExecutingJar().finalizeValueOnRead();
        getMainClass().finalizeValueOnRead();
    }

    @TaskAction
    public void execute() throws Throwable {
        final Provider<List<String>> jvmArgs = applyVariableSubstitutions(getJvmArguments());
        final Provider<List<String>> programArgs = applyVariableSubstitutions(getProgramArguments());

        final Provider<File> outputFile = ensureFileWorkspaceReady(getOutputFile());
        final Provider<File> logFile = ensureFileWorkspaceReady(getLogFile());
        final Provider<File> consoleLogFile = ensureFileWorkspaceReady(getConsoleLogFile());

        final Provider<String> mainClass = getMainClass();
        final Provider<String> executable = getExecutablePath();

        try (BufferedOutputStream log_out = new BufferedOutputStream(new FileOutputStream(consoleLogFile.get()))) {
            getProject().javaexec(java -> {
                PrintWriter writer = new PrintWriter(log_out);
                Function<String, String> quote = s -> '"' + s + '"';
                writer.println("JVM:               " + executable.get());
                writer.println("JVM Args:          " + jvmArgs.get().stream().map(quote).collect(Collectors.joining(", ")));
                writer.println("Run Args:          " + programArgs.get().stream().map(quote).collect(Collectors.joining(", ")));
                writer.println("Classpath:         " + getExecutingJar().get().getAsFile().getAbsolutePath());
                writer.println("Working Dir:       " + getOutputDirectory().get().getAsFile().getAbsolutePath());
                writer.println("Main Class:        " + mainClass.get());
                writer.println("Program log file:  " + logFile.get().getAbsolutePath());
                writer.println("Output file:       " + outputFile.get().getAbsolutePath());
                writer.flush();

                java.executable(executable.get());
                java.setJvmArgs(jvmArgs.get());
                java.setArgs(programArgs.get());
                java.setClasspath(getProject().files(getExecutingJar().get()));
                java.setWorkingDir(getOutputDirectory().get());
                java.getMainClass().set(mainClass);
                java.setStandardOutput(log_out);
            }).rethrowFailure().assertNormalExitValue();
        }
    }

    private Provider<List<String>> applyVariableSubstitutions(Provider<List<String>> list) {
        return list.map(values -> values.stream().map(this::applyVariableSubstitutions).collect(Collectors.toList()));
    }

    private String applyVariableSubstitutions(String value) {
        final Map<String, Object> runtimeArguments = getRuntimeArguments().get();
        final Map<String, FileWrapper> data = getRuntimeData().get();

        Matcher matcher = REPLACE_PATTERN.matcher(value);
        if (!matcher.find()) return value; // Not a replaceable string

        String argName = matcher.group(1);
        if (argName != null) {
            Object argument = runtimeArguments.get(argName);
            if (argument instanceof File) {
                return ((File)argument).getAbsolutePath();
            } else if (argument instanceof String) {
                return (String)argument;
            }

            FileWrapper dataElement = data.get(argName);
            if (dataElement != null) {
                return dataElement.file().getAbsolutePath();
            }
        }

        throw new IllegalStateException("The string '" + value + "' did not return a valid substitution match!");
    }

    @Input
    public abstract Property<String> getConsoleLogFileName();

    @Input
    public abstract Property<String> getLogFileName();

    @Input
    public abstract ListProperty<String> getJvmArguments();

    @Input
    public abstract ListProperty<String> getProgramArguments();

    @Input
    public abstract Property<String> getExecutingArtifact();

    @Input
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getExecutingJar();

    @Input
    public abstract Property<String> getMainClass();

    @OutputFile
    public abstract RegularFileProperty getConsoleLogFile();

    @OutputFile
    public abstract RegularFileProperty getLogFile();

    @Override
    protected void buildRuntimeArguments(Map<String, Object> arguments) {
        super.buildRuntimeArguments(arguments);
        arguments.computeIfAbsent("log", k -> getLogFile().get().getAsFile().getAbsolutePath());
        arguments.computeIfAbsent("console.log", k -> getConsoleLogFile().get().getAsFile().getAbsolutePath());
    }
}
