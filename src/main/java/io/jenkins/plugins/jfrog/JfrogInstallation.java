package io.jenkins.plugins.jfrog;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * @author gail
 */
public class JfrogInstallation extends ToolInstallation
        implements NodeSpecific<JfrogInstallation>, EnvironmentSpecific<JfrogInstallation> {

    public static final String JFROG_BINARY_PATH = "JFROG_BINARY_PATH";
    public static final String JFROG_CLI_DEPENDENCIES_DIR = "JFROG_CLI_DEPENDENCIES_DIR";
    public static final String JFROG_CLI_USER_AGENT = "JFROG_CLI_USER_AGENT";
    public static final String JfrogDependenciesDirName = "dependencies";

    @DataBoundConstructor
    public JfrogInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    public JfrogInstallation forEnvironment(EnvVars environment) {
        return new JfrogInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    public JfrogInstallation forNode(@NonNull Node node, TaskListener log) throws IOException, InterruptedException {
        return new JfrogInstallation(getName(), translateFor(node, log), getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String home = getHome();
        if (home == null) {
            return;
        }
        env.put(JFROG_BINARY_PATH, home);
        if (env.get(JFROG_CLI_DEPENDENCIES_DIR) == null) {
            // Jfrog CLI dependencies directory is a sibling of all the other tools directories.
            // By doing this, we avoid downloading dependencies separately for each job in its temporary Jfrog home directory.
            Path path = Paths.get(home).getParent();
            if (path != null) {
                env.put(JFROG_CLI_DEPENDENCIES_DIR, path.resolve(JfrogDependenciesDirName).toString());
            }
        }
        env.putIfAbsent(JFROG_CLI_USER_AGENT, "jenkins-jfrog-plugin" + getPluginVersion());
    }

    private String getPluginVersion() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return "";
        }
        Plugin plugin = jenkins.getPlugin("jfrog");
        if (plugin == null) {
            return "";
        }
        PluginWrapper wrapper = plugin.getWrapper();
        if (wrapper == null) {
            return "";
        }
        String version = wrapper.getVersion();
        // Return only the version prefix, without the agent information.
        return "/" + version.split(" ")[0];
    }

    @Symbol("jfrog")
    @Extension
    public static final class DescriptorImpl extends ToolDescriptor<JfrogInstallation> {

        public DescriptorImpl() {
            super(JfrogInstallation.class);
            load();
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "JFrog CLI";
        }

        @Override
        public JfrogInstallation newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return (JfrogInstallation) super.newInstance(req, formData.getJSONObject("jfrog"));
        }

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            List<ToolInstaller> installersList = new ArrayList<>();
            // The default installation will be from 'releases.jfrog.io'
            installersList.add(new ReleasesInstaller());
            return installersList;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null && jenkins.hasPermission(Jenkins.ADMINISTER)) {
                super.configure(req, o);
                save();
                return true;
            }
            throw new FormException("User doesn't have permissions to save", "Server ID");
        }
    }
}
