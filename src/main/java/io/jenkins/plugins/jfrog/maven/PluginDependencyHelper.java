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
 */
public class PluginDependencyHelper {

    static final String EXTRACTOR_LIB_RESOURCE = "maven-extractor-lib";
    private static final String JAR_LIST_RESOURCE = EXTRACTOR_LIB_RESOURCE + "/jars.list";

    private PluginDependencyHelper() {
    }

    public static FilePath getActualDependencyDirectory(FilePath rootPath)
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
        // Invalidate cache if:
        // 1. The "done" mark does not exist (first extraction)
        // 2. The cached plugin version differs from the current version (plugin was upgraded)
        // This ensures plugin upgrades refresh cached extractor jars without time-dependency flakiness.
        if (!remoteDependencyMark.exists()) {
            copyExtractorLibResources(remoteDependencyDir);
            writeCacheVersionMark(remoteDependencyMark, rawVersion);
        } else if (cacheVersionChanged(remoteDependencyMark, rawVersion)) {
            deleteDirectoryContents(remoteDependencyDir);
            copyExtractorLibResources(remoteDependencyDir);
            writeCacheVersionMark(remoteDependencyMark, rawVersion);
        }
        if (isSnapshot) {
            remoteDependencyDir.act(new DeleteOnExitCallable());
        }
        return remoteDependencyDir;
    }

    private static void writeCacheVersionMark(FilePath mark, String version) {
        try {
            // Write the current plugin version to the mark file for future comparison
            String content = StringUtils.defaultIfBlank(version, "unknown");
            mark.write(content, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            // If we cannot write the mark, the cache will be invalidated on next run
        }
    }

    private static boolean cacheVersionChanged(FilePath mark, String currentVersion) {
        try {
            String cachedVersion = mark.readToString();
            String current = StringUtils.defaultIfBlank(currentVersion, "unknown");
            // Invalidate if cached version differs from current version
            return !current.equals(cachedVersion);
        } catch (Exception e) {
            // If we cannot read the mark, assume cache is stale
            return true;
        }
    }

    private static void deleteDirectoryContents(FilePath dir) {
        try {
            for (FilePath child : dir.list()) {
                if (!child.getName().equals("done")) {
                    child.delete();
                }
            }
        } catch (IOException | InterruptedException e) {
            // Ignore cleanup errors; we'll overwrite the files anyway
        }
    }

    static String cacheVersion(String pluginVersion, long timestamp) {
        if (StringUtils.isBlank(pluginVersion)) {
            // When version is unknown, use a static marker. Version invalidation
            // (cacheVersionChanged) will refresh the cache if the stored version differs
            // from the current version. This eliminates time-dependency flakiness.
            return "unknown";
        }
        if (pluginVersion.contains(" ")) {
            return StringUtils.split(pluginVersion, " ")[0] + "-" + timestamp;
        }
        return pluginVersion;
    }

    private static String resolvePluginVersion() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || jenkins.getPluginManager() == null) {
            return null;
        }
        PluginWrapper plugin = jenkins.getPluginManager().getPlugin("jfrog");
        return plugin == null ? null : plugin.getVersion();
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
                if (in == null) {
                    throw new IOException("Missing extractor jar on the plugin classpath: " +
                            EXTRACTOR_LIB_RESOURCE + "/" + jarName);
                }
                remoteJar.copyFrom(in);
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
