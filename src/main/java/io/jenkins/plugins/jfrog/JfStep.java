package io.jenkins.plugins.jfrog;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import io.jenkins.cli.shaded.org.apache.commons.io.FilenameUtils;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformBuilder;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;

/**
 * @author gail
 */
@SuppressWarnings("unused")
public class JfStep<T> extends Builder implements SimpleBuildStep {
    static final String STEP_NAME = "jf";
    static final String JFROG_CLI_HOME_DIR = "JFROG_CLI_HOME_DIR";
    protected String args;

    @DataBoundConstructor
    public JfStep(String args) {
        this.args = args;
    }

    public String getArgs() {
        return args;
    }

    /**
     * Build and run a 'jf' command.
     *
     * @param run       running as a part of a specific build
     * @param workspace a workspace to use for any file operations
     * @param env       environment variables applicable to this step
     * @param launcher  a way to start processes
     * @param listener  a place to send output
     * @throws InterruptedException if the step is interrupted
     * @throws IOException          in case of any I/O error, or we failed to run the 'jf' command
     */
    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull EnvVars env, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        workspace.mkdirs();
        // Build the 'jf' command
        ArgumentListBuilder builder = new ArgumentListBuilder();
        boolean isWindows = !launcher.isUnix();
        // JFROG_BINARY_PATH is set according to the main OS.
        // We should convert JFROG_BINARY_PATH to run on the relevant slave OS if main and slave run on different operating systems.
        String jfrogBinaryPath = Paths.get(env.get(JFROG_BINARY_PATH), Utils.getJfrogCliBinaryName(isWindows)).toString();
        if (isWindows) {
            jfrogBinaryPath = FilenameUtils.separatorsToWindows(jfrogBinaryPath);
        } else {
            jfrogBinaryPath = FilenameUtils.separatorsToUnix(jfrogBinaryPath);
        }
        builder.add(jfrogBinaryPath);
        builder.add(StringUtils.split(args));
        if (isWindows) {
            builder = builder.toWindowsCommand();
        }

        try {
            // Set up a temporary Jfrog CLI home directory for a specific run.
            FilePath jfrogHomeTempDir = Utils.createAndGetJfrogCliHomeTempDir(workspace, String.valueOf(run.getNumber()));
            env.put(JFROG_CLI_HOME_DIR, jfrogHomeTempDir.getRemote());
            Launcher.ProcStarter jfLauncher = launcher.launch().envs(env).pwd(workspace).stdout(listener);
            // Configure all servers, skip if all server ids have already been configured.
            if (shouldConfig(jfrogHomeTempDir)) {
                configAllServers(jfLauncher, jfrogBinaryPath, isWindows, run.getParent());
            }
            // Running the 'jf' command
            int exitValue = jfLauncher.cmds(builder).join();
            if (exitValue != 0) {
                throw new RuntimeException("Running 'jf' command failed with exit code " + exitValue);
            }
        } catch (Exception e) {
            String errorMessage = "Couldn't execute 'jf' command. " + ExceptionUtils.getRootCauseMessage(e);
            throw new RuntimeException(errorMessage, e);
        }
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
        if (jfrogInstances != null && jfrogInstances.size() > 0) {
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
        builder.add("--artifactory-url=" + jfrogPlatformInstance.getArtifactoryUrl());
        builder.add("--distribution-url=" + jfrogPlatformInstance.getDistributionUrl());
        builder.add("--xray-url=" + jfrogPlatformInstance.getXrayUrl());

        builder.add("--interactive=false");
        // The installation process takes place more than once per build, so we will configure the same server ID several times.
        builder.add("--overwrite=true");

    }

    @Symbol("jf")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "jf command";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}