package io.jenkins.plugins.jfrog;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.jfrog.build.client.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.*;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.jfrog.actions.BuildInfoBuildBadgeAction;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.models.BuildInfoOutputModel;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import lombok.Getter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jfrog.build.api.util.Log;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;
import static org.apache.commons.lang3.StringUtils.*;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.createMapper;

/**
 * @author gail
 */
@Getter
@SuppressWarnings("unused")
public class JfStep extends Step {
    private static final ObjectMapper mapper = createMapper();
    protected String[] args;
    static final Version MIN_CLI_VERSION_PASSWORD_STDIN = new Version("2.31.3");

    @DataBoundConstructor
    public JfStep(Object args) {
        if (args instanceof List) {
            //noinspection unchecked
            this.args = ((List<String>) args).toArray(String[]::new);
            return;
        }
        this.args = split(args.toString());
    }

    /**
     * Retrieves the version of the JFrog CLI.
     *
     * @param launcher        The process launcher used to execute the JFrog CLI command.
     * @param jfrogBinaryPath The path to the JFrog CLI binary.
     * @return The version of the JFrog CLI.
     * @throws IOException          If an I/O error occurs while executing the command or reading the output.
     * @throws InterruptedException If the process is interrupted while waiting for the command to complete.
     */
    public static Version getJfrogCliVersion(Launcher.ProcStarter launcher, String jfrogBinaryPath) throws IOException, InterruptedException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ArgumentListBuilder builder = new ArgumentListBuilder();
            builder.add(jfrogBinaryPath).add("-v");
            int exitCode = launcher
                    .cmds(builder)
                    .pwd(launcher.pwd())
                    .stdout(outputStream)
                    .join();
            if (exitCode != 0) {
                throw new IOException("Failed to get JFrog CLI version: " + outputStream.toString(StandardCharsets.UTF_8));
            }
            String versionOutput = outputStream.toString(StandardCharsets.UTF_8).trim();
            String version = StringUtils.substringAfterLast(versionOutput, " ");
            return new Version(version);
        }
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(args, context);
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<String> {
        private final String[] args;

        protected Execution(String[] args, @Nonnull StepContext context) {
            super(context);
            this.args = args;
        }

        @Override
        protected String run() throws Exception {
            // Get the step context
            Launcher launcher = getContext().get(Launcher.class);
            FilePath workspace = getContext().get(FilePath.class);
            TaskListener listener = getContext().get(TaskListener.class);
            EnvVars env = getContext().get(EnvVars.class);
            Run<?, ?> run = getContext().get(Run.class);

            workspace.mkdirs();
            // Build the 'jf' command
            ArgumentListBuilder builder = new ArgumentListBuilder();
            boolean isWindows = !launcher.isUnix();
            String jfrogBinaryPath = getJFrogCLIPath(env, isWindows);
            boolean passwordStdinSupported = isPasswordStdinEnabled(workspace, env, launcher, jfrogBinaryPath);

            builder.add(jfrogBinaryPath).add(args);
            if (isWindows) {
                builder = builder.toWindowsCommand();
            }

            String output;
            try (ByteArrayOutputStream taskOutputStream = new ByteArrayOutputStream()) {
                JfTaskListener jfTaskListener = new JfTaskListener(listener, taskOutputStream);
                Launcher.ProcStarter jfLauncher = setupJFrogEnvironment(run, env, launcher, jfTaskListener, workspace, jfrogBinaryPath, isWindows, passwordStdinSupported);
                // Running the 'jf' command
                int exitValue = jfLauncher.cmds(builder).join();
                output = taskOutputStream.toString(StandardCharsets.UTF_8);
                if (exitValue != 0) {
                    throw new RuntimeException("Running 'jf' command failed with exit code " + exitValue);
                }
                addBuildInfoActionIfNeeded(args, new JenkinsBuildInfoLog(listener), run, taskOutputStream);
            } catch (Exception e) {
                String errorMessage = "Couldn't execute 'jf' command. " + ExceptionUtils.getRootCauseMessage(e);
                throw new RuntimeException(errorMessage, e);
            }
            return output;
        }

        /**
         * Get JFrog CLI path in agent, according to the JFROG_BINARY_PATH environment variable.
         * The JFROG_BINARY_PATH also can be set implicitly in Declarative Pipeline by choosing the JFrog CLI tool or
         * explicitly in Scripted Pipeline.
         *
         * @param env       - Job's environment variables
         * @param isWindows - True if the agent's OS is windows
         * @return JFrog CLI path in agent.
         */
        static String getJFrogCLIPath(EnvVars env, boolean isWindows) {
            // JFROG_BINARY_PATH is set according to the master OS. If not configured, the value of jfrogBinaryPath will
            // eventually be 'jf' or 'jf.exe'. In that case, the JFrog CLI from the system path is used.
            String jfrogBinaryPath = Paths.get(env.get(JFROG_BINARY_PATH, ""), Utils.getJfrogCliBinaryName(isWindows)).toString();

            // Modify jfrogBinaryPath according to the agent's OS
            return isWindows ?
                    FilenameUtils.separatorsToWindows(jfrogBinaryPath) :
                    FilenameUtils.separatorsToUnix(jfrogBinaryPath);
        }

        /**
         * Log if the JFrog CLI binary path doesn't exist in job's environment variable.
         * This environment variable exists in one of the following scenarios:
         * 1. Declarative Pipeline: A 'jfrog' tool was set
         * 2. Scripted Pipeline: Using the "withEnv(["JFROG_BINARY_PATH=${tool 'jfrog-cli'}"])" syntax
         *
         * @param env      - Job's environment variables
         * @param listener - Job's logger
         */
        private void logIfNoToolProvided(EnvVars env, TaskListener listener) {
            if (env.containsKey(JFROG_BINARY_PATH)) {
                return;
            }
            JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(listener);
            buildInfoLog.info("A 'jfrog' tool was not set. Using JFrog CLI from the system path.");
        }

        /**
         * Configure all JFrog relevant environment variables and all servers (if they haven't been configured yet).
         *
         * @param run                    running as part of a specific build
         * @param env                    environment variables applicable to this step
         * @param launcher               a way to start processes
         * @param listener               a place to send output
         * @param workspace              a workspace to use for any file operations
         * @param jfrogBinaryPath        path to jfrog cli binary on the filesystem
         * @param isWindows              is Windows the applicable OS
         * @param passwordStdinSupported indicates if the password can be securely passed via stdin to the CLI
         * @return launcher applicable to this step.
         * @throws InterruptedException if the step is interrupted
         * @throws IOException          in case of any I/O error, or we failed to run the 'jf' command
         */
        public Launcher.ProcStarter setupJFrogEnvironment(Run<?, ?> run, EnvVars env, Launcher launcher, TaskListener listener, FilePath workspace, String jfrogBinaryPath, boolean isWindows, boolean passwordStdinSupported) throws IOException, InterruptedException {
            JFrogCliConfigEncryption jfrogCliConfigEncryption = run.getAction(JFrogCliConfigEncryption.class);
            if (jfrogCliConfigEncryption == null) {
                // Set up the config encryption action to allow encrypting the JFrog CLI configuration and make sure we only create one key
                jfrogCliConfigEncryption = new JFrogCliConfigEncryption(env);
                run.addAction(jfrogCliConfigEncryption);
            }
            FilePath jfrogHomeTempDir = Utils.createAndGetJfrogCliHomeTempDir(workspace, String.valueOf(run.getNumber()));
            CliEnvConfigurator.configureCliEnv(env, jfrogHomeTempDir.getRemote(), jfrogCliConfigEncryption);
            Launcher.ProcStarter jfLauncher = launcher.launch().envs(env).pwd(workspace).stdout(listener);
            // Configure all servers, skip if all server ids have already been configured.
            if (shouldConfig(jfrogHomeTempDir)) {
                logIfNoToolProvided(env, listener);
                configAllServers(jfLauncher, jfrogBinaryPath, isWindows, run.getParent(), passwordStdinSupported);
            }
            return jfLauncher;
        }

        /**
         * This functionality determines whether a password can be securely passed to the CLI via stdin
         * instead of using the `--password` flag. This capability depends on two key factors:
         * 1. The JFrog CLI version installed on the agent (the minimum supported version is 2.31.3).
         * 2. The status of the `JFROG_CLI_PASSWORD_STDIN_SUPPORT` variable, which defaults to empty.
         * If `JFROG_CLI_PASSWORD_STDIN_SUPPORT` is not set, the system will automatically fall back
         * to detecting the plugin launcher. If it is set, this variable will guide whether
         * stdin is supported, provided that the CLI version exceeds `MIN_CLI_VERSION_PASSWORD_STDIN`.
         * <p>
         * Note: The primary reason for this limitation is that Docker plugin which is widely used
         * does not support stdin input, because it is a custom launcher.
         * @param environmentVariables The environment variables.
         * @return true if stdin-based password handling is supported; false otherwise.
         */
        public boolean isPasswordStdinEnabled(FilePath workspace, EnvVars environmentVariables, Launcher launcher, String jfrogBinaryPath) throws IOException, InterruptedException {
            TaskListener listener = getContext().get(TaskListener.class);
            JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(listener);
            String readJFrogCliPwdStdinSupport = environmentVariables.get("JFROG_CLI_PASSWORD_STDIN_SUPPORT", "");
            Launcher.ProcStarter procStarter = launcher.launch().envs(environmentVariables).pwd(workspace);
            Version currentCliVersion = getJfrogCliVersion(procStarter, jfrogBinaryPath);
            boolean isMinimumCLIVersionPasswdSTDIN = currentCliVersion.isAtLeast(MIN_CLI_VERSION_PASSWORD_STDIN);
            if (StringUtils.isBlank(readJFrogCliPwdStdinSupport)) {
                boolean isPluginLauncher = launcher.getClass().getName().contains("org.jenkinsci.plugins");
                if (isPluginLauncher) {
                    buildInfoLog.debug("Password stdin is not supported,Launcher is a plugin launcher.");
                    return false;
                }
                buildInfoLog.debug("Password stdin is supported");
                return isMinimumCLIVersionPasswdSTDIN;
            }
    boolean isSupported = Boolean.parseBoolean(readJFrogCliPwdStdinSupport);
    if (isSupported && !isMinimumCLIVersionPasswdSTDIN) {
        buildInfoLog.error("Password input via stdin is not supported, JFrog CLI version is below the minimum required version.");
    }
    return isSupported && isMinimumCLIVersionPasswdSTDIN;
        }

        /**
         * Before we run a 'jf' command for the first time, we want to configure all servers first.
         * We know that all servers have already been configured if there is a "jfrog-cli.conf" file in the ".jfrog" home directory.
         *
         * @param jfrogHomeTempDir - The temp ".jfrog" directory path.
         */
        private boolean shouldConfig(FilePath jfrogHomeTempDir) throws IOException, InterruptedException {
            List<FilePath> filesList = jfrogHomeTempDir.list();
            for (FilePath file : filesList) {
                if (file.getName().contains("jfrog-cli.conf")) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Locally configure all servers that was configured in the Jenkins UI.
         */
        private void configAllServers(Launcher.ProcStarter launcher, String jfrogBinaryPath, boolean isWindows, Job<?, ?> job, boolean passwordStdinSupported) throws IOException, InterruptedException {
            // Config all servers using the 'jf c add' command.
            List<JFrogPlatformInstance> jfrogInstances = JFrogPlatformBuilder.getJFrogPlatformInstances();
            if (jfrogInstances != null && !jfrogInstances.isEmpty()) {
                for (JFrogPlatformInstance jfrogPlatformInstance : jfrogInstances) {
                    // Build 'jf' command
                    ArgumentListBuilder builder = new ArgumentListBuilder();
                    addConfigArguments(builder, jfrogPlatformInstance, job, jfrogBinaryPath, job, launcher, passwordStdinSupported);
                    if (isWindows) {
                        builder = builder.toWindowsCommand();
                    }
                    // Running 'jf' command
                    int exitValue = launcher.cmds(builder).join();
                    if (exitValue != 0) {
                        throw new RuntimeException("Running 'jf' command failed with exit code " + exitValue);
                    }
                }
            }
        }

        private void addConfigArguments(ArgumentListBuilder builder, JFrogPlatformInstance jfrogPlatformInstance, Job<?, ?> job1, String jfrogBinaryPath, Job<?, ?> job, Launcher.ProcStarter launcher, boolean passwordStdinSupported) {
            builder.add(jfrogBinaryPath).add("c").add("add").add(jfrogPlatformInstance.getId());
            addCredentialsArguments(builder, jfrogPlatformInstance, job, launcher, passwordStdinSupported);
            addUrlArguments(builder, jfrogPlatformInstance);
            builder.add("--interactive=false").add("--overwrite=true");
        }
    }

    static void addCredentialsArguments(ArgumentListBuilder builder, JFrogPlatformInstance jfrogPlatformInstance, Job<?, ?> job, Launcher.ProcStarter launcher, boolean passwordStdinSupported) {
        String credentialsId = jfrogPlatformInstance.getCredentialsConfig().getCredentialsId();
        StringCredentials accessTokenCredentials = PluginsUtils.accessTokenCredentialsLookup(credentialsId, job);

        if (accessTokenCredentials != null) {
            builder.addMasked("--access-token=" + accessTokenCredentials.getSecret().getPlainText());
        } else {
            Credentials credentials = PluginsUtils.credentialsLookup(credentialsId, job);
            builder.add("--user=" + credentials.getUsername());
            addPasswordArgument(builder, credentials, launcher, passwordStdinSupported);
        }
    }

    /**
     * Configures the CLI command to provide a password via stdin if supported; otherwise,
     * uses the default `--password` argument. Stdin support requires a minimum CLI version
     * and does not work with certain plugin launchers, as they may lose stdin input,
     * potentially causing command failures.
     * <p>
     * This method ensures compatibility by switching to the default password argument when
     * stdin is unsupported or unsuitable for the launcher being used.
     *
     * @param builder                The {@link ArgumentListBuilder} used to construct the CLI command arguments.
     * @param credentials            The {@link Credentials} object containing the user's password.
     * @param launcher               The {@link Launcher.ProcStarter} used to execute the command.
     * @param passwordStdinSupported A boolean flag indicating whether the CLI supports password input via stdin.
     */
    private static void addPasswordArgument(ArgumentListBuilder builder, Credentials credentials, Launcher.ProcStarter launcher, boolean passwordStdinSupported) {
        if (passwordStdinSupported) {
            // Add argument to read password from stdin
            builder.add("--password-stdin");
            ByteArrayInputStream inputStream = new ByteArrayInputStream(credentials.getPassword().getPlainText().getBytes(StandardCharsets.UTF_8));
            launcher.stdin(inputStream);
        } else {
            // Add masked password argument directly to the command
            builder.addMasked("--password=" + credentials.getPassword());
        }
    }

    private static void addUrlArguments(ArgumentListBuilder builder, JFrogPlatformInstance jfrogPlatformInstance) {
        builder.add("--url=" + jfrogPlatformInstance.getUrl());
        builder.add("--artifactory-url=" + jfrogPlatformInstance.inferArtifactoryUrl());
        builder.add("--distribution-url=" + jfrogPlatformInstance.inferDistributionUrl());
        builder.add("--xray-url=" + jfrogPlatformInstance.inferXrayUrl());
    }

    /**
     * Add build-info Action if the command is 'jf rt bp' or 'jf rt build-publish'.
     *
     * @param log              - Task logger
     * @param run              - The Jenkins project
     * @param taskOutputStream - Task's output stream
     */
    static void addBuildInfoActionIfNeeded(String[] args, Log log, Run<?, ?> run, ByteArrayOutputStream taskOutputStream) {
        if (args.length < 2 ||
                !args[0].equals("rt") ||
                !equalsAny(args[1], "bp", "build-publish")) {
            return;
        }

        // Search for '{' and '}' in the output of 'jf rt build-publish'
        String taskOutput = taskOutputStream.toString(StandardCharsets.UTF_8);
        taskOutput = substringBetween(taskOutput, "{", "}");
        if (taskOutput == null) {
            logIllegalBuildPublishOutput(log, taskOutputStream);
            return;
        }

        // Parse the output into BuildInfoOutputModel to extract the build-info URL
        BuildInfoOutputModel buildInfoOutputModel;
        try {
            buildInfoOutputModel = mapper.readValue("{" + taskOutput + "}", BuildInfoOutputModel.class);
            if (buildInfoOutputModel == null) {
                logIllegalBuildPublishOutput(log, taskOutputStream);
                return;
            }
        } catch (JsonProcessingException e) {
            logIllegalBuildPublishOutput(log, taskOutputStream);
            log.warn(ExceptionUtils.getRootCauseMessage(e));
            return;
        }
        String buildInfoUrl = buildInfoOutputModel.getBuildInfoUiUrl();

        // Add the BuildInfoBuildBadgeAction action into the job to show the build-info button
        if (isNotBlank(buildInfoUrl)) {
            run.addAction(new BuildInfoBuildBadgeAction(buildInfoUrl));
        }
    }

    private static void logIllegalBuildPublishOutput(Log log, ByteArrayOutputStream taskOutputStream) {
        log.warn("Illegal build-publish output: " + taskOutputStream.toString(StandardCharsets.UTF_8));
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "jf";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "jf command";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Launcher.class, FilePath.class, TaskListener.class, EnvVars.class);
        }
    }
}
