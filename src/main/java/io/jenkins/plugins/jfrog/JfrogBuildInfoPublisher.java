package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static io.jenkins.plugins.jfrog.JfStep.addBuildInfoActionIfNeeded;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;

/**
 * Post-build action to publish JFrog Build Info.
 * This automatically runs 'jf rt build-publish' after the build completes,
 * publishing collected build information to Artifactory.
 */
public class JfrogBuildInfoPublisher extends Notifier {

    private String jfrogInstallation;
    private boolean publishOnlyOnSuccess = true;

    @DataBoundConstructor
    public JfrogBuildInfoPublisher() {
    }

    public String getJfrogInstallation() {
        return jfrogInstallation;
    }

    @DataBoundSetter
    public void setJfrogInstallation(String jfrogInstallation) {
        this.jfrogInstallation = jfrogInstallation;
    }

    public boolean isPublishOnlyOnSuccess() {
        return publishOnlyOnSuccess;
    }

    @DataBoundSetter
    public void setPublishOnlyOnSuccess(boolean publishOnlyOnSuccess) {
        this.publishOnlyOnSuccess = publishOnlyOnSuccess;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        
        // Check if we should skip based on build result
        Result buildResult = build.getResult();
        if (publishOnlyOnSuccess && buildResult != null && buildResult.isWorseThan(Result.SUCCESS)) {
            listener.getLogger().println("[JFrog Build Info] Skipping publish - build result is " + buildResult);
            return true;
        }

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            listener.error("[JFrog Build Info] Workspace is null");
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

        // Check if JFROG_BINARY_PATH is set (either from installation or Build Environment wrapper)
        if (!env.containsKey(JFROG_BINARY_PATH)) {
            listener.getLogger().println("[JFrog Build Info] Using JFrog CLI from system PATH");
        }

        boolean isWindows = !launcher.isUnix();
        String jfrogBinaryPath = Utils.getJFrogCLIPath(env, isWindows);

        // Setup JFrog environment
        JFrogCliConfigEncryption jfrogCliConfigEncryption = build.getAction(JFrogCliConfigEncryption.class);
        if (jfrogCliConfigEncryption == null) {
            jfrogCliConfigEncryption = new JFrogCliConfigEncryption(env);
            build.addAction(jfrogCliConfigEncryption);
        }

        FilePath jfrogHomeTempDir = Utils.createAndGetJfrogCliHomeTempDir(workspace, String.valueOf(build.getNumber()));
        CliEnvConfigurator.configureCliEnv(env, jfrogHomeTempDir.getRemote(), jfrogCliConfigEncryption);

        // Build the 'jf rt build-publish' command
        ArgumentListBuilder builder = new ArgumentListBuilder();
        builder.add(jfrogBinaryPath).add("rt").add("bp");
        if (isWindows) {
            builder = builder.toWindowsCommand();
        }

        listener.getLogger().println("[JFrog Build Info] Publishing build info...");
        listener.getLogger().println("[JFrog Build Info] Build name: " + env.get("JFROG_CLI_BUILD_NAME"));
        listener.getLogger().println("[JFrog Build Info] Build number: " + env.get("JFROG_CLI_BUILD_NUMBER"));

        try (ByteArrayOutputStream taskOutputStream = new ByteArrayOutputStream()) {
            JfTaskListener jfTaskListener = new JfTaskListener(listener, taskOutputStream);
            Launcher.ProcStarter jfLauncher = launcher.launch()
                    .envs(env)
                    .pwd(workspace)
                    .stdout(jfTaskListener);

            // Configure servers if needed
            if (shouldConfig(jfrogHomeTempDir)) {
                JfStep.Execution.configAllServersForBuilder(
                        jfLauncher, jfrogBinaryPath, isWindows, build.getParent(), false
                );
            }

            // Run 'jf rt bp'
            int exitValue = jfLauncher.cmds(builder).join();
            if (exitValue != 0) {
                listener.error("[JFrog Build Info] Failed to publish build info (exit code: " + exitValue + ")");
                return false;
            }

            // Add build info badge to Jenkins UI
            String[] args = {"rt", "bp"};
            addBuildInfoActionIfNeeded(args, new JenkinsBuildInfoLog(listener), build, taskOutputStream);
            
            listener.getLogger().println("[JFrog Build Info] Build info published successfully");
            return true;
        } catch (Exception e) {
            listener.error("[JFrog Build Info] Error publishing build info: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if servers need to be configured.
     */
    private boolean shouldConfig(FilePath jfrogHomeTempDir) throws IOException, InterruptedException {
        if (jfrogHomeTempDir == null || !jfrogHomeTempDir.exists()) {
            return true;
        }
        
        for (FilePath file : jfrogHomeTempDir.list()) {
            if (file != null && file.getName().contains("jfrog-cli.conf")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the JFrog installation by name.
     */
    private JfrogInstallation getInstallation() {
        if (jfrogInstallation == null) {
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

    @Extension
    @Symbol("jfrogPublishBuildInfo")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Publish JFrog Build Info";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        /**
         * Get all configured JFrog CLI installations.
         */
        public JfrogInstallation[] getInstallations() {
            jenkins.model.Jenkins jenkinsInstance = jenkins.model.Jenkins.get();
            return jenkinsInstance.getDescriptorByType(JfrogInstallation.DescriptorImpl.class).getInstallations();
        }

        /**
         * Populate the dropdown list of JFrog CLI installations.
         */
        public ListBoxModel doFillJfrogInstallationItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("(Use pre-installed JFrog CLI from system PATH)", "");
            for (JfrogInstallation installation : getInstallations()) {
                items.add(installation.getName(), installation.getName());
            }
            return items;
        }
    }
}
