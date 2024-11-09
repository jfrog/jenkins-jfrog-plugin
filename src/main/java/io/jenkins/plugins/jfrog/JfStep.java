package io.jenkins.plugins.jfrog;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jfrog.build.api.util.Log;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
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

    @DataBoundConstructor
    public JfStep(Object args) {
        if (args instanceof List) {
            //noinspection unchecked
            this.args = ((List<String>) args).toArray(String[]::new);
            return;
        }
        this.args = split(args.toString());
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

            builder.add(jfrogBinaryPath).add(args);
            if (isWindows) {
                builder = builder.toWindowsCommand();
            }

            String output;
            try (ByteArrayOutputStream taskOutputStream = new ByteArrayOutputStream()) {
                JfTaskListener jfTaskListener = new JfTaskListener(listener, taskOutputStream);
                Launcher.ProcStarter jfLauncher = setupJFrogEnvironment(run, env, launcher, jfTaskListener, workspace, jfrogBinaryPath, isWindows);
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
         * @param run             running as part of a specific build
         * @param env             environment variables applicable to this step
         * @param launcher        a way to start processes
         * @param listener        a place to send output
         * @param workspace       a workspace to use for any file operations
         * @param jfrogBinaryPath path to jfrog cli binary on the filesystem
         * @param isWindows       is Windows the applicable OS
         * @return launcher applicable to this step.
         * @throws InterruptedException if the step is interrupted
         * @throws IOException          in case of any I/O error, or we failed to run the 'jf' command
         */
        public Launcher.ProcStarter setupJFrogEnvironment(Run<?, ?> run, EnvVars env, Launcher launcher, TaskListener listener, FilePath workspace, String jfrogBinaryPath, boolean isWindows) throws IOException, InterruptedException {
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
                configAllServers(jfLauncher, jfrogBinaryPath, isWindows, run.getParent());
            }
            return jfLauncher;
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
        private void configAllServers(Launcher.ProcStarter launcher, String jfrogBinaryPath, boolean isWindows, Job<?, ?> job) throws IOException, InterruptedException {
            // Config all servers using the 'jf c add' command.
            List<JFrogPlatformInstance> jfrogInstances = JFrogPlatformBuilder.getJFrogPlatformInstances();
            if (jfrogInstances != null && !jfrogInstances.isEmpty()) {
                for (JFrogPlatformInstance jfrogPlatformInstance : jfrogInstances) {
                    // Build 'jf' command
                    ArgumentListBuilder builder = new ArgumentListBuilder();
                    addConfigArguments(builder, jfrogPlatformInstance, jfrogBinaryPath, job);
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

        private void addConfigArguments(ArgumentListBuilder builder, JFrogPlatformInstance jfrogPlatformInstance, String jfrogBinaryPath, Job<?, ?> job) {
            String credentialsId = jfrogPlatformInstance.getCredentialsConfig().getCredentialsId();
            builder.add(jfrogBinaryPath).add("c").add("add").add(jfrogPlatformInstance.getId());
            // Add credentials
            StringCredentials accessTokenCredentials = PluginsUtils.accessTokenCredentialsLookup(credentialsId, job);
            if (accessTokenCredentials != null) {
                builder.addMasked("--access-token=" + accessTokenCredentials.getSecret().getPlainText());
            } else {
                Credentials credentials = PluginsUtils.credentialsLookup(credentialsId, job);
                builder.add("--user=" + credentials.getUsername());
                builder.addMasked("--password=" + credentials.getPassword());
            }
            // Add URLs
            builder.add("--url=" + jfrogPlatformInstance.getUrl());
            builder.add("--artifactory-url=" + jfrogPlatformInstance.inferArtifactoryUrl());
            builder.add("--distribution-url=" + jfrogPlatformInstance.inferDistributionUrl());
            builder.add("--xray-url=" + jfrogPlatformInstance.inferXrayUrl());

            builder.add("--interactive=false");
            // The installation process takes place more than once per build, so we will configure the same server ID several times.
            builder.add("--overwrite=true");
        }
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
