package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildWrapper;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Build Environment wrapper that sets up JFrog CLI for the entire build.
 * This allows all build steps in a Freestyle job to use the configured JFrog CLI
 * installation without having to specify it in each step.
 * 
 * When this wrapper is enabled, the JFROG_BINARY_PATH environment variable is set
 * for the entire build, making the 'jf' command available to all build steps,
 * including shell scripts and other plugins.
 * 
 * <p><b>Note:</b> This wrapper is only available for Freestyle jobs, not Matrix jobs.
 * Matrix jobs run across multiple nodes where CLI installations may differ.
 * For Matrix jobs, use individual "Run JFrog CLI" build steps with automatic
 * installation configured, which will download the CLI to each node as needed.</p>
 */
public class JfrogCliWrapper extends SimpleBuildWrapper {

    private String jfrogInstallation;

    @DataBoundConstructor
    public JfrogCliWrapper() {
    }

    public String getJfrogInstallation() {
        return jfrogInstallation;
    }

    @DataBoundSetter
    public void setJfrogInstallation(String jfrogInstallation) {
        this.jfrogInstallation = jfrogInstallation;
    }

    @Override
    public void setUp(
            Context context,
            Run<?, ?> build,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            EnvVars initialEnvironment
    ) throws IOException, InterruptedException {
        if (jfrogInstallation == null || jfrogInstallation.isEmpty()) {
            listener.getLogger().println("[JFrog CLI] No installation selected, using system PATH");
            return;
        }

        JfrogInstallation installation = getInstallation();
        if (installation == null) {
            listener.error("[JFrog CLI] Installation '" + jfrogInstallation + "' not found");
            return;
        }

        // Resolve the installation for the current node
        hudson.model.Node node = workspaceToNode(workspace);
        if (node != null) {
            installation = installation.forNode(node, listener);
            if (installation == null) {
                listener.error("[JFrog CLI] Installation '" + jfrogInstallation + "' is not available for current node");
                return;
            }
        }
        installation = installation.forEnvironment(initialEnvironment);
        if (installation == null) {
            listener.error("[JFrog CLI] Installation '" + jfrogInstallation + "' is not available for current environment");
            return;
        }

        // Add environment variables that will persist for the entire build
        EnvVars envVars = new EnvVars();
        installation.buildEnvVars(envVars);
        
        for (java.util.Map.Entry<String, String> entry : envVars.entrySet()) {
            context.env(entry.getKey(), entry.getValue());
        }

        listener.getLogger().println("[JFrog CLI] Using installation: " + jfrogInstallation);
        listener.getLogger().println("[JFrog CLI] Binary path: " + envVars.get(JfrogInstallation.JFROG_BINARY_PATH));
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

    /**
     * Get the node from workspace.
     */
    private hudson.model.Node workspaceToNode(FilePath workspace) {
        jenkins.model.Jenkins jenkinsInstance = jenkins.model.Jenkins.getInstanceOrNull();
        if (jenkinsInstance == null || workspace == null) {
            return null;
        }

        // Check if workspace is on master
        if (workspace.getChannel() == jenkinsInstance.getChannel()) {
            return jenkinsInstance;
        }

        // Find the node that owns this workspace
        for (hudson.model.Node node : jenkinsInstance.getNodes()) {
            if (node.getChannel() == workspace.getChannel()) {
                return node;
            }
        }
        return null;
    }

    @Extension
    @Symbol("jfrogCliEnv")
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Set up JFrog CLI environment";
        }

        /**
         * This wrapper is only available for Freestyle jobs, not Matrix jobs.
         * 
         * Matrix jobs run across multiple nodes where CLI installations need to be
         * handled per-node. For Matrix jobs, users should use individual "Run JFrog CLI"
         * build steps with automatic installation configured, which will download
         * the CLI to each node as needed.
         * 
         * @param item The project to check
         * @return true if this wrapper can be used with the project
         */
        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            // Exclude Matrix projects - they run across multiple nodes
            // Use class name check to avoid hard dependency on matrix-project plugin
            String className = item.getClass().getName();
            if (className.contains("MatrixProject") || className.contains("MatrixConfiguration")) {
                return false;
            }
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
