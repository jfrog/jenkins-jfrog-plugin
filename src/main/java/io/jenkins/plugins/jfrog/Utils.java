package io.jenkins.plugins.jfrog;

import hudson.FilePath;
import hudson.model.Job;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.callables.TempDirCreator;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jfrog.build.client.ProxyConfiguration;

import java.io.IOException;

/**
 * @author gail
 */
public class Utils {
    public static final String BINARY_NAME = "jf";

    public static FilePath getWorkspace(Job<?, ?> project) {
        FilePath projectJob = new FilePath(project.getRootDir());
        FilePath workspace = projectJob.getParent();
        if (workspace == null) {
            throw new RuntimeException("Failed to get job workspace.");
        }
        workspace = workspace.sibling("workspace");
        if (workspace == null) {
            throw new RuntimeException("Failed to get job workspace.");
        }
        return workspace.child(project.getName());
    }

    public static String getJfrogCliBinaryName(boolean isWindows) {
        if (isWindows) {
            return BINARY_NAME + ".exe";
        }
        return BINARY_NAME;
    }

    /**
     * Delete temp jfrog cli home directory associated with the build number.
     *
     * @param ws           - The workspace
     * @param buildNumber  - The build number
     * @param taskListener - The logger
     */
    public static void deleteBuildJfrogHomeDir(FilePath ws, String buildNumber, TaskListener taskListener) {
        try {
            FilePath jfrogCliHomeDir = createAndGetJfrogCliHomeTempDir(ws, buildNumber);
            jfrogCliHomeDir.deleteRecursive();
            taskListener.getLogger().println(jfrogCliHomeDir.getRemote() + " deleted");
        } catch (IOException | InterruptedException e) {
            taskListener.getLogger().println("Failed while attempting to delete the JFrog CLI home dir \n" + ExceptionUtils.getRootCauseMessage(e));
        }
    }

    /**
     * Create a temporary jfrog cli home directory under a given workspace
     */
    public static FilePath createAndGetTempDir(final FilePath ws) throws IOException, InterruptedException {
        // The token that combines the project name and unique number to create unique workspace directory.
        String workspaceList = System.getProperty("hudson.slaves.WorkspaceList");
        return ws.act(new TempDirCreator(workspaceList, ws));
    }

    public static FilePath createAndGetJfrogCliHomeTempDir(final FilePath ws, String buildNumber) throws IOException, InterruptedException {
        return createAndGetTempDir(ws).child(buildNumber).child(".jfrog");
    }

    /**
     * Get the proxy configuration from Jenkins and create a proxy configuration suitable to the ArtifactoryManager.
     *
     * @return the proxy configuration
     */
    public static ProxyConfiguration createProxyConfiguration() {
        hudson.ProxyConfiguration proxy = Jenkins.get().getProxy();
        if (proxy == null) {
            return null;
        }
        ProxyConfiguration proxyConfiguration = new ProxyConfiguration();
        proxyConfiguration.host = proxy.getName();
        proxyConfiguration.port = proxy.getPort();
        proxyConfiguration.username = proxy.getUserName();
        proxyConfiguration.password = Secret.toString(proxy.getSecretPassword());
        return proxyConfiguration;
    }
}
