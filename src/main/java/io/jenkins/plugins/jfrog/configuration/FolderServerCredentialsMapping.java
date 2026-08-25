package io.jenkins.plugins.jfrog.configuration;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.jfrog.plugins.PluginsUtils;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;

/**
 * A single mapping between a globally configured JFrog server ID and a folder-scoped
 * Jenkins credential that should be used for it.
 * <p>
 * These mappings are stored on a {@link JFrogFolderProperty} and let jobs inside a folder
 * override the credentials that were configured globally for a given JFrog server ID,
 * without having to reconfigure the server through the CLI.
 */
public class FolderServerCredentialsMapping extends AbstractDescribableImpl<FolderServerCredentialsMapping> {

    private final String serverId;
    private final String credentialsId;

    @DataBoundConstructor
    public FolderServerCredentialsMapping(String serverId, String credentialsId) {
        this.serverId = trimToEmpty(serverId);
        this.credentialsId = trimToEmpty(credentialsId);
    }

    public String getServerId() {
        return serverId;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @Extension
    @Symbol("jfrogServerCredentials")
    public static class DescriptorImpl extends Descriptor<FolderServerCredentialsMapping> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "JFrog server credentials mapping";
        }

        /**
         * Populate the dropdown with the JFrog server IDs configured globally.
         */
        @SuppressWarnings("unused")
        public ListBoxModel doFillServerIdItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("-- Select a server --", "");
            List<JFrogPlatformInstance> instances = JFrogPlatformBuilder.getJFrogPlatformInstances();
            if (instances != null) {
                for (JFrogPlatformInstance instance : instances) {
                    if (instance != null && instance.getId() != null) {
                        items.add(instance.getId(), instance.getId());
                    }
                }
            }
            return items;
        }

        /**
         * Flag a mapping saved without a server ID, so it is caught in the UI rather than
         * silently ignored when credentials are resolved at build time.
         */
        @SuppressWarnings("unused")
        public FormValidation doCheckServerId(@QueryParameter String value) {
            return isBlank(value)
                    ? FormValidation.error("Select a JFrog server ID for this mapping.")
                    : FormValidation.ok();
        }

        /**
         * Flag a mapping saved without a credential, so it is caught in the UI rather than
         * silently ignored when credentials are resolved at build time.
         */
        @SuppressWarnings("unused")
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            return isBlank(value)
                    ? FormValidation.error("Select the credentials to use for this JFrog server ID.")
                    : FormValidation.ok();
        }

        /**
         * Populate the credentials dropdown, scoped to the folder that is currently being configured.
         * This is what makes folder-level credentials selectable for a JFrog server ID.
         *
         * @param item          the item (folder) being configured, injected by Stapler
         * @param credentialsId the currently selected credentials id
         * @return the list of credentials the user is allowed to select
         */
        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(credentialsId);
            }
            return PluginsUtils.fillPluginCredentials(item);
        }
    }
}
