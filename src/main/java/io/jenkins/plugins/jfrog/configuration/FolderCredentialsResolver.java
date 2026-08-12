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

    private FolderCredentialsResolver() {
        // Utility class
    }

    /**
     * Resolve the folder-scoped credentials override for the given job and JFrog server ID.
     *
     * @param job      the job that will run the JFrog CLI command (may be {@code null})
     * @param serverId the JFrog server ID being configured
     * @return the resolved override, or {@code null} if no enclosing folder overrides this server
     */
    public static Resolution resolve(Job<?, ?> job, String serverId) {
        if (job == null || StringUtils.isBlank(serverId)) {
            return null;
        }
        try {
            ItemGroup<?> parent = job.getParent();
            while (parent != null) {
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
        } catch (Throwable t) {
            // The cloudbees-folder plugin might be unavailable at runtime, or the lookup could fail.
            // In either case we fall back to the globally configured credentials.
            logger.log(Level.FINE, "Failed to resolve folder-level JFrog credentials for server '" + serverId + "'", t);
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
