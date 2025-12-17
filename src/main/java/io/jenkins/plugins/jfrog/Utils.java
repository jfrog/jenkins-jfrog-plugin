package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.TaskListener;
import io.jenkins.plugins.jfrog.callables.TempDirCreator;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.IOException;
import java.nio.file.Paths;

import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;

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
     * Get JFrog CLI path in agent, according to the JFROG_BINARY_PATH environment variable.
     * The JFROG_BINARY_PATH can be set implicitly by choosing the JFrog CLI tool in the job configuration
     * or explicitly by setting the environment variable.
     *
     * @param env       Job's environment variables
     * @param isWindows True if the agent's OS is Windows
     * @return JFrog CLI path in agent (e.g., "/path/to/jf" or "C:\path\to\jf.exe")
     */
    public static String getJFrogCLIPath(EnvVars env, boolean isWindows) {
        // JFROG_BINARY_PATH is set according to the master OS. If not configured, the value of jfrogBinaryPath will
        // eventually be 'jf' or 'jf.exe'. In that case, the JFrog CLI from the system path is used.
        String jfrogBinaryPath = Paths.get(env.get(JFROG_BINARY_PATH, ""), getJfrogCliBinaryName(isWindows)).toString();

        // Modify jfrogBinaryPath according to the agent's OS
        return isWindows ?
                FilenameUtils.separatorsToWindows(jfrogBinaryPath) :
                FilenameUtils.separatorsToUnix(jfrogBinaryPath);
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
}
