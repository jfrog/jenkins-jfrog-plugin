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
 * <p>
 * This is a stateless utility class, not an instantiable service: every input needed to resolve
 * an override ({@code job}, {@code serverId}) is passed into {@link #resolve}, so there is no
 * per-instance state to hold. Static members and a private constructor keep that explicit and
 * prevent callers from instantiating an object that would carry no state.
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

    /**
     * cloudbees-folder is an optional dependency: it may not be installed in a given Jenkins
     * instance. {@code resolve} below uses {@code instanceof AbstractFolder} and casts to it, and
     * the JVM resolves those references (triggering class loading) the first time this class's
     * bytecode runs, not just when the branch is taken. Probing for the class explicitly here,
     * inside a try/catch, lets us detect a missing plugin gracefully instead of failing with a
     * {@link LinkageError} the first time {@code resolve} executes.
     */
    private static boolean isFolderPluginPresent() {
        try {
            Class.forName("com.cloudbees.hudson.plugins.folder.AbstractFolder");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            // Expected when cloudbees-folder isn't installed — not an error, so FINE rather than
            // WARNING; folder-level overrides are simply unavailable and global credentials apply.
            logger.log(Level.FINE, "cloudbees-folder plugin not available; folder-level JFrog credential overrides are disabled", e);
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
            // Same "plugin isn't really there" case as isFolderPluginPresent() above (e.g. the
            // plugin was uninstalled between the class-load check and this call) — expected in a
            // dynamic Jenkins install, not a bug, so FINE rather than WARNING.
            logger.log(Level.FINE, "cloudbees-folder plugin unavailable while resolving folder-level JFrog credentials for server '" + serverId + "'", e);
        } catch (RuntimeException e) {
            // Unlike the case above, this means the plugin IS present but something about the
            // folder configuration or credential lookup itself failed unexpectedly. That must stay
            // visible to operators — WARNING, not INFO/FINE — otherwise the silent fall back to
            // global credentials is hard to diagnose (e.g. builds failing "unauthorized" against
            // the wrong server with no clue why).
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
