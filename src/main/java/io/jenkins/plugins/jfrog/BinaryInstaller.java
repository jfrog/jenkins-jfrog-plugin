package io.jenkins.plugins.jfrog;

import hudson.FilePath;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import io.jenkins.plugins.jfrog.callables.JFrogCliDownloader;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import org.jfrog.build.client.ProxyConfiguration;

import java.io.IOException;

import static io.jenkins.plugins.jfrog.Utils.createProxyConfiguration;


/**
 * Installer for JFrog CLI binary.
 *
 * @author gail
 */
public abstract class BinaryInstaller extends ToolInstaller {
    protected BinaryInstaller(String label) {
        super(label);
    }

    /**
     * @param tool the tool being installed.
     * @param node the computer on which to install the tool.
     * @return Node's filesystem location where a tool should be installed.
     */
    protected FilePath getToolLocation(ToolInstallation tool, Node node) throws IOException, InterruptedException {
        FilePath location = preferredLocation(tool, node);
        if (!location.exists()) {
            location.mkdirs();
        }
        return location;
    }

    public abstract static class DescriptorImpl<T extends BinaryInstaller> extends ToolInstallerDescriptor<T> {
        /**
         * This ID needs to be unique, and needs to match the ID token in the JSON update file.
         * <p>
         * By default, we use the fully-qualified class name of the {@link BinaryInstaller} subtype.
         */
        @Override
        public String getId() {
            return clazz.getName().replace('$', '.');
        }
    }

    public static FilePath performJfrogCliInstallation(FilePath toolLocation, TaskListener log, String version, JFrogPlatformInstance instance, String repository, String binaryName) throws IOException, InterruptedException {
        ProxyConfiguration proxyConfiguration = createProxyConfiguration();
        // Download Jfrog CLI binary
        toolLocation.act(new JFrogCliDownloader(proxyConfiguration, version, instance, log, repository, binaryName));
        return toolLocation;
    }
}

