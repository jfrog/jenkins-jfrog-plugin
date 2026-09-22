package io.jenkins.plugins.jfrog.maven;

import hudson.FilePath;
import hudson.PluginWrapper;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies Maven-extractor jars onto the build node so the forked Maven JVM can load them.
 * Jackson/Commons for that JVM live under {@code maven-extractor-lib/}, not as extra
 * Jenkins-side {@code WEB-INF/lib} copies.
 */
public class PluginDependencyHelper {

    static final String EXTRACTOR_LIB_RESOURCE = "maven-extractor-lib";
    private static final String JAR_LIST_RESOURCE = EXTRACTOR_LIB_RESOURCE + "/jars.list";

    private PluginDependencyHelper() {
    }

    public static FilePath getActualDependencyDirectory(File localDependencyFile, FilePath rootPath)
            throws IOException, InterruptedException {
        if (rootPath == null) {
            throw new IOException("Cannot copy JFrog Maven extractor jars: build node root is not available");
        }
        long currentTime = System.currentTimeMillis();
        String rawVersion = resolvePluginVersion();
        boolean isSnapshot = rawVersion != null && rawVersion.contains(" ");
        FilePath remoteDependencyDir = new FilePath(rootPath, "cache/jfrog-plugin/" + cacheVersion(rawVersion, currentTime));
        remoteDependencyDir.mkdirs();

        FilePath remoteDependencyMark = new FilePath(remoteDependencyDir, "done");
        if (!remoteDependencyMark.exists()) {
            copyWebInfJars(localDependencyFile, remoteDependencyDir);
            copyExtractorLibResources(remoteDependencyDir);
            remoteDependencyMark.touch(currentTime);
        }
        if (isSnapshot) {
            remoteDependencyDir.act(new DeleteOnExitCallable());
        }
        return remoteDependencyDir;
    }

    static String cacheVersion(String pluginVersion, long timestamp) {
        if (StringUtils.isBlank(pluginVersion)) {
            return "unknown";
        }
        if (pluginVersion.contains(" ")) {
            return StringUtils.split(pluginVersion, " ")[0] + "-" + timestamp;
        }
        return pluginVersion;
    }

    static boolean shouldSkipJenkinsPluginJar(String name) {
        return "classes.jar".equals(name)
                || "jfrog.jar".equals(name)
                || name.startsWith("build-info-extractor-maven3-");
    }

    private static String resolvePluginVersion() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || jenkins.getPluginManager() == null) {
            return null;
        }
        PluginWrapper plugin = jenkins.getPluginManager().getPlugin("jfrog");
        return plugin == null ? null : plugin.getVersion();
    }

    private static void copyWebInfJars(File localDependencyFile, FilePath remoteDependencyDir)
            throws IOException, InterruptedException {
        File localDependencyDir = localDependencyFile == null ? null : localDependencyFile.getParentFile();
        File[] localDependencies = localDependencyDir == null ? null : localDependencyDir.listFiles();
        if (localDependencies == null) {
            return;
        }
        for (File localDependency : localDependencies) {
            if (shouldSkipJenkinsPluginJar(localDependency.getName())) {
                continue;
            }
            FilePath remote = new FilePath(remoteDependencyDir, localDependency.getName());
            if (!remote.exists()) {
                new FilePath(localDependency).copyTo(remote);
            }
        }
    }

    private static void copyExtractorLibResources(FilePath remoteDependencyDir)
            throws IOException, InterruptedException {
        ClassLoader classLoader = PluginDependencyHelper.class.getClassLoader();
        for (String jarName : readExtractorJarNames(classLoader)) {
            FilePath remoteJar = new FilePath(remoteDependencyDir, jarName);
            if (remoteJar.exists()) {
                continue;
            }
            try (InputStream in = classLoader.getResourceAsStream(EXTRACTOR_LIB_RESOURCE + "/" + jarName)) {
                if (in != null) {
                    remoteJar.copyFrom(in);
                }
            }
        }
    }

    static List<String> readExtractorJarNames(ClassLoader classLoader) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = classLoader.getResourceAsStream(JAR_LIST_RESOURCE)) {
            if (in == null) {
                return names;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String jarName = line.trim();
                    if (jarName.endsWith(".jar")) {
                        names.add(jarName);
                    }
                }
            }
        }
        return names;
    }

    private static final class DeleteOnExitCallable extends MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;

        @Override
        public Void invoke(File file, VirtualChannel channel) {
            file.deleteOnExit();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.deleteOnExit();
                }
            }
            return null;
        }
    }
}
