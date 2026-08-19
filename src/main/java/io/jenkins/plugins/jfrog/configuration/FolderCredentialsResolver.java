package io.jenkins.plugins.jfrog.configuration;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import org.apache.commons.lang3.StringUtils;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves folder-level credential overrides for JFrog server IDs.
 * <p>
 * Given the job that is about to run a {@code jf} command, this walks up the folder hierarchy
 * looking for a {@link JFrogFolderProperty} that overrides the credentials for the requested
 * JFrog server ID. The nearest enclosing folder that defines an override wins; if no folder
 * overrides the server, {@code null} is returned and the caller falls back to the globally
 * configured credentials.
 */
public final class FolderCredentialsResolver {
    private static final Logger logger = Logger.getLogger(FolderCredentialsResolver.class.getName());

    /**
     * Defensive upper bound on how many levels we walk up the item hierarchy. The folder plugin
     * should never produce a cycle, but a bound guarantees this can never hang.
     */
    private static final int MAX_HOPS = 100;

    /**
     * Whether the cloudbees-folder plugin is available in this Jenkins. Resolved once at class
     * load time so we never touch {@link AbstractFolder} bytecode when the plugin is missing.
     */
    private static final boolean FOLDER_PLUGIN_PRESENT = isFolderPluginPresent();

    private FolderCredentialsResolver() {
        // Utility class
    }

    private static boolean isFolderPluginPresent() {
        try {
            Class.forName("com.cloudbees.hudson.plugins.folder.AbstractFolder");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            logger.log(Level.FINE, "cloudbees-folder plugin not available; folder-level JFrog credentials disabled", e);
            return false;
        }
    }

    /**
     * Resolve the folder-scoped credentials override for the given job and JFrog server ID.
     *
     * @param job      the job that will run the JFrog CLI command (may be {@code null})
     * @param serverId the JFrog server ID being configured
     * @return the resolved override, or {@code null} if no enclosing folder overrides this server
     */
    public static Resolution resolve(Job<?, ?> job, String serverId) {
        if (!FOLDER_PLUGIN_PRESENT || job == null || StringUtils.isBlank(serverId)) {
            return null;
        }
        try {
            ItemGroup<?> parent = job.getParent();
            int hops = 0;
            while (parent != null && hops++ < MAX_HOPS) {
                if (parent instanceof AbstractFolder) {
                    AbstractFolder<?> folder = (AbstractFolder<?>) parent;
                    JFrogFolderProperty property = folder.getProperties().get(JFrogFolderProperty.class);
                    if (property != null) {
                        String credentialsId = property.getCredentialsIdForServer(serverId);
                        if (StringUtils.isNotBlank(credentialsId)) {
                            return new Resolution(credentialsId, folder);
                        }
                    }
                }
                // Move one level up the item hierarchy. The Jenkins root is an ItemGroup but not an
                // Item, so the loop terminates there.
                if (parent instanceof Item) {
                    parent = ((Item) parent).getParent();
                } else {
                    break;
                }
            }
        } catch (LinkageError e) {
            // The cloudbees-folder plugin became unavailable at runtime; fall back to global creds.
            logger.log(Level.FINE, "cloudbees-folder plugin unavailable while resolving folder-level JFrog credentials for server '" + serverId + "'", e);
        } catch (RuntimeException e) {
            // Anything unexpected (misconfiguration, lookup failure) must be visible to operators,
            // otherwise the silent fall back to global credentials is hard to diagnose.
            logger.log(Level.WARNING, "Failed to resolve folder-level JFrog credentials for server '" + serverId + "'; falling back to global credentials", e);
        }
        return null;
    }

    /**
     * The result of a successful folder-credential resolution: the credentials id to use and the
     * folder that owns it (used as the lookup context so folder-scoped credentials resolve).
     */
    public static final class Resolution {
        private final String credentialsId;
        private final Item context;

        Resolution(String credentialsId, Item context) {
            this.credentialsId = credentialsId;
            this.context = context;
        }

        public String getCredentialsId() {
            return credentialsId;
        }

        public Item getContext() {
            return context;
        }
    }
}
