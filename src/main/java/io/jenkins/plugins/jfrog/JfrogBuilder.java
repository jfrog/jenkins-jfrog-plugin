package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import lombok.Getter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static io.jenkins.plugins.jfrog.JfStep.*;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;
import static org.apache.commons.lang3.StringUtils.split;

/**
 * Builder for executing JFrog CLI commands in Freestyle jobs.
 * This builder allows users to run JFrog CLI commands as a build step.
 * 
 * @author Jenkins JFrog Plugin Team
 */
@Getter
public class JfrogBuilder extends Builder {
    private static final String JFROG_CLI_CONFIG_FILE = "jfrog-cli.conf";
    
    private String command;
    private String jfrogInstallation;

    @DataBoundConstructor
    public JfrogBuilder(String command) {
        this.command = command;
    }

    @DataBoundSetter
    public void setCommand(String command) {
        this.command = command;
    }

    @DataBoundSetter
    public void setJfrogInstallation(String jfrogInstallation) {
        this.jfrogInstallation = jfrogInstallation;
    }

    /**
     * Executes the JFrog CLI command as part of a freestyle build step.
     * This method validates the command, sets up the JFrog CLI environment,
     * executes the command, and processes any build-info artifacts.
     *
     * @param build    The current build being executed
     * @param launcher The launcher to execute commands on the build node
     * @param listener The build listener for logging output and errors
     * @return true if the command executed successfully with exit code 0, false otherwise
     * @throws InterruptedException if the build is interrupted during execution
     * @throws IOException          if there's an I/O error accessing the workspace or executing commands
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            listener.error("Workspace is null");
            return false;
        }
        EnvVars env = build.getEnvironment(listener);
        
        // Setup JFrog CLI installation environment if specified
        if (StringUtils.isNotBlank(jfrogInstallation)) {
            JfrogInstallation installation = getInstallation();
            if (installation != null) {
                hudson.model.Node node = build.getBuiltOn();
                if (node != null) {
                    installation = installation.forNode(node, listener);
                }
                if (installation != null) {
                    installation = installation.forEnvironment(env);
                }
                if (installation != null) {
                    installation.buildEnvVars(env);
                }
            }
        }
        
        workspace.mkdirs();

        // Parse command
        if (StringUtils.isBlank(command)) {
            listener.error("No JFrog CLI command provided");
            return false;
        }
        
        String trimmedCommand = command.trim();
        
        // Validate that the command starts with 'jf' or 'jfrog' followed by subcommand/arguments
        if (!trimmedCommand.startsWith("jf ") && !trimmedCommand.startsWith("jfrog ")) {
            listener.error("JFrog CLI command must start with 'jf' or 'jfrog' followed by a subcommand (e.g., 'jf rt ping' or 'jfrog rt ping')");
            return false;
        }
        
        // Parse the command and remove the 'jf' or 'jfrog' prefix
        String[] fullArgs = split(trimmedCommand);
        String[] args;
        if (fullArgs.length > 0 && (fullArgs[0].equals("jf") || fullArgs[0].equals("jfrog"))) {
            // Remove the 'jf' or 'jfrog' prefix since we add it back when building the command
            args = new String[fullArgs.length - 1];
            System.arraycopy(fullArgs, 1, args, 0, args.length);
        } else {
            args = fullArgs;
        }

        // Build the 'jf' command
        ArgumentListBuilder builder = new ArgumentListBuilder();
        boolean isWindows = !launcher.isUnix();
        String jfrogBinaryPath = Utils.getJFrogCLIPath(env, isWindows);
        boolean passwordStdinSupported = isPasswordStdinEnabled(workspace, env, launcher, jfrogBinaryPath, listener);

        builder.add(jfrogBinaryPath).add(args);
        if (isWindows) {
            builder = builder.toWindowsCommand();
        }

        try (ByteArrayOutputStream taskOutputStream = new ByteArrayOutputStream()) {
            JfTaskListener jfTaskListener = new JfTaskListener(listener, taskOutputStream);
            Launcher.ProcStarter jfLauncher = setupJFrogEnvironment(
                    build, env, launcher, jfTaskListener, workspace, 
                    jfrogBinaryPath, isWindows, passwordStdinSupported, listener  // Pass original listener for console logging
            );

            // Running the 'jf' command
            int exitValue = jfLauncher.cmds(builder).join();
            if (exitValue != 0) {
                listener.error("Running 'jf' command failed with exit code " + exitValue);
                listener.error("Please check the console output above for detailed error information.");
                return false;
            }

            addBuildInfoActionIfNeeded(args, new JenkinsBuildInfoLog(listener), build, taskOutputStream);
            return true;
        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().contains("No such file or directory") 
                    || e.getMessage().contains("Cannot run program"))) {
                listener.error("JFrog CLI (jf) not found. Please configure JFrog CLI as a tool:");
                listener.error("  1. Go to 'Manage Jenkins' → 'Global Tool Configuration'");
                listener.error("  2. Add JFrog CLI installation under 'JFrog CLI' section");
                listener.error("  3. Either set automatic installation or provide the path to JFrog CLI");
                listener.error("Error details: " + ExceptionUtils.getRootCauseMessage(e));
            } else {
                listener.error("Couldn't execute 'jf' command. " + ExceptionUtils.getRootCauseMessage(e));
            }
            return false;
        } catch (Exception e) {
            String errorMessage = "Couldn't execute 'jf' command. " + ExceptionUtils.getRootCauseMessage(e);
            listener.error(errorMessage);
            return false;
        }
    }

    /**
     * Get the JFrog installation by name.
     */
    private JfrogInstallation getInstallation() {
        if (jfrogInstallation == null) {
            return null;
        }
        
        if (!(getDescriptor() instanceof DescriptorImpl)) {
            return null;
        }
        
        JfrogInstallation[] installations = ((DescriptorImpl) getDescriptor()).getInstallations();
        if (installations == null) {
            return null;
        }
        
        for (JfrogInstallation installation : installations) {
            if (installation != null && jfrogInstallation.equals(installation.getName())) {
                return installation;
            }
        }
        return null;
    }

    /**
     * Get JFrog CLI path in agent, according to the JFROG_BINARY_PATH environment variable.
     *
     * @param env       - Job's environment variables
     * @param isWindows - True if the agent's OS is windows
     * @return JFrog CLI path in agent.
     */
    static String getJFrogCLIPath(EnvVars env, boolean isWindows) {
        String jfrogBinaryPath = Paths.get(env.get(JFROG_BINARY_PATH, ""), Utils.getJfrogCliBinaryName(isWindows)).toString();

        // Modify jfrogBinaryPath according to the agent's OS
        return isWindows ?
                FilenameUtils.separatorsToWindows(jfrogBinaryPath) :
                FilenameUtils.separatorsToUnix(jfrogBinaryPath);
    }

    /**
     * Configure all JFrog relevant environment variables and all servers.
     * Creates a temporary JFrog CLI home directory for this build, sets up server configurations,
     * and prepares the launcher with the necessary environment.
     * 
     * <p><b>Note on Resource Management:</b> The temporary JFrog CLI home directory created by this method
     * is intended to persist for the duration of the build. Cleanup of this directory should be handled
     * by the build completion logic if necessary, not within this method.</p>
     *
     * @param run                      The current build/run
     * @param env                      Environment variables for the build
     * @param launcher                 The launcher to execute commands
     * @param cliOutputListener        Task listener for capturing JFrog CLI command output
     * @param workspace                The build workspace
     * @param jfrogBinaryPath          Path to the JFrog CLI binary
     * @param isWindows                Whether the agent OS is Windows
     * @param passwordStdinSupported   Whether password input via stdin is supported
     * @param consoleListener          Build listener for console logging messages
     * @return Configured ProcStarter ready to execute JFrog CLI commands
     * @throws IOException          If there's an error creating directories or configuring servers
     * @throws InterruptedException If the operation is interrupted
     */
    private Launcher.ProcStarter setupJFrogEnvironment(
            Run<?, ?> run, EnvVars env, Launcher launcher, TaskListener cliOutputListener,
            FilePath workspace, String jfrogBinaryPath, boolean isWindows,
            boolean passwordStdinSupported, TaskListener consoleListener
    ) throws IOException, InterruptedException {
        JFrogCliConfigEncryption jfrogCliConfigEncryption = run.getAction(JFrogCliConfigEncryption.class);
        if (jfrogCliConfigEncryption == null) {
            jfrogCliConfigEncryption = new JFrogCliConfigEncryption(env);
            run.addAction(jfrogCliConfigEncryption);
        }

        FilePath jfrogHomeTempDir = Utils.createAndGetJfrogCliHomeTempDir(workspace, String.valueOf(run.getNumber()));
        CliEnvConfigurator.configureCliEnv(env, jfrogHomeTempDir, jfrogCliConfigEncryption);
        Launcher.ProcStarter jfLauncher = launcher.launch().envs(env).pwd(workspace).stdout(cliOutputListener);

        // Configure all servers, skip if all server ids have already been configured.
        if (shouldConfig(jfrogHomeTempDir)) {
            logIfNoToolProvided(env, consoleListener);
            JfStep.Execution.configAllServersForBuilder(
                    jfLauncher, jfrogBinaryPath, isWindows, run.getParent(), passwordStdinSupported
            );
        }
        return jfLauncher;
    }

    /**
     * Check if servers need to be configured.
     */
    private boolean shouldConfig(FilePath jfrogHomeTempDir) throws IOException, InterruptedException {
        if (jfrogHomeTempDir == null || !jfrogHomeTempDir.exists()) {
            return true;
        }
        
        List<FilePath> filesList = jfrogHomeTempDir.list();
        for (FilePath file : filesList) {
            if (file != null && file.getName().contains(JFROG_CLI_CONFIG_FILE)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Log if no JFrog CLI tool was provided.
     */
    private void logIfNoToolProvided(EnvVars env, TaskListener listener) {
        if (!env.containsKey(JFROG_BINARY_PATH)) {
            JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(listener);
            buildInfoLog.info("A 'jfrog' tool was not set. Using JFrog CLI from the system path.");
        }
    }

    /**
     * Check if password stdin is supported.
     * Returns false if JFrog CLI is not available (will be checked later during execution).
     */
    private boolean isPasswordStdinEnabled(FilePath workspace, EnvVars env, Launcher launcher,
                                           String jfrogBinaryPath, TaskListener listener) {
        try {
            JenkinsBuildInfoLog buildInfoLog = new JenkinsBuildInfoLog(listener);
            String readJFrogCliPwdStdinSupport = env.get("JFROG_CLI_PASSWORD_STDIN_SUPPORT", "");
            Launcher.ProcStarter procStarter = launcher.launch().envs(env).pwd(workspace);
            
            org.jfrog.build.client.Version currentCliVersion = getJfrogCliVersion(procStarter, jfrogBinaryPath);
            boolean isMinimumCLIVersionPasswdSTDIN = currentCliVersion.isAtLeast(JfStep.MIN_CLI_VERSION_PASSWORD_STDIN);
            
            if (StringUtils.isBlank(readJFrogCliPwdStdinSupport)) {
                boolean isPluginLauncher = launcher.getClass().getName().contains("org.jenkinsci.plugins");
                if (isPluginLauncher) {
                    buildInfoLog.debug("Password stdin is not supported, Launcher is a plugin launcher.");
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
        } catch (IOException | InterruptedException e) {
            // If we can't determine CLI version (e.g., CLI not found), default to not using stdin
            // The actual error about missing CLI will be caught during command execution
            return false;
        }
    }

    @Extension
    @Symbol("jfrogCli")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Run JFrog CLI";
        }

        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        /**
         * Get all configured JFrog CLI installations.
         */
        public JfrogInstallation[] getInstallations() {
            return jenkins.model.Jenkins.get()
                    .getDescriptorByType(JfrogInstallation.DescriptorImpl.class)
                    .getInstallations();
        }

        /**
         * Populate the dropdown list of JFrog CLI installations.
         */
        public ListBoxModel doFillJfrogInstallationItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(Use JFrog CLI from system PATH)", "");
            for (JfrogInstallation installation : getInstallations()) {
                items.add(installation.getName(), installation.getName());
            }
            return items;
        }
    }
}
