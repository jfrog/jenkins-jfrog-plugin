package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

/**
 * Configures JFrog CLI environment variables for the job.
 *
 * @author yahavi
 **/
public class CliEnvConfigurator {
    static final String JFROG_CLI_DEFAULT_EXCLUSIONS = "*password*;*psw*;*secret*;*key*;*token*;*auth*";
    static final String JFROG_CLI_ENCRYPTION_KEY = "JFROG_CLI_ENCRYPTION_KEY";
    static final String JFROG_CLI_BUILD_NUMBER = "JFROG_CLI_BUILD_NUMBER";
    public static final String JFROG_CLI_HOME_DIR = "JFROG_CLI_HOME_DIR";
    static final String JFROG_CLI_ENV_EXCLUDE = "JFROG_CLI_ENV_EXCLUDE";
    static final String JFROG_CLI_BUILD_NAME = "JFROG_CLI_BUILD_NAME";
    static final String JFROG_CLI_BUILD_URL = "JFROG_CLI_BUILD_URL";
    static final String HTTPS_PROXY_ENV = "HTTPS_PROXY";
    static final String HTTP_PROXY_ENV = "HTTP_PROXY";
    static final String NO_PROXY = "NO_PROXY";

    /**
     * Configure the JFrog CLI environment variables, according to the input job's env.
     *
     * @param env              - Job's environment variables
     * @param jfrogHomeTempDir - Calculated JFrog CLI home dir (FilePath on the agent)
     * @param encryptionKey    - Random encryption key to encrypt the CLI config
     * @throws IOException if the encryption key file cannot be written
     * @throws InterruptedException if the operation is interrupted
     */
    static void configureCliEnv(EnvVars env, FilePath jfrogHomeTempDir, JFrogCliConfigEncryption encryptionKey) throws IOException, InterruptedException {
        // Setting Jenkins job name as the default build-info name
        env.putIfAbsent(JFROG_CLI_BUILD_NAME, env.get("JOB_NAME"));
        // Setting Jenkins build number as the default build-info number
        env.putIfAbsent(JFROG_CLI_BUILD_NUMBER, env.get("BUILD_NUMBER"));
        // Setting the specific build URL
        env.putIfAbsent(JFROG_CLI_BUILD_URL, env.get("BUILD_URL"));
        // Set up a temporary Jfrog CLI home directory for a specific run.
        // Use getRemote() to get the path as seen by the agent.
        env.put(JFROG_CLI_HOME_DIR, jfrogHomeTempDir.getRemote());
        if (StringUtils.isAllBlank(env.get(HTTP_PROXY_ENV), env.get(HTTPS_PROXY_ENV))) {
            // Set up HTTP/S proxy
            setupProxy(env);
        }
        if (encryptionKey.shouldEncrypt()) {
            // Write the encryption key file on the agent (not controller) using FilePath.
            // This ensures the file exists where the JFrog CLI runs (Docker/remote agent).
            String keyFilePath = encryptionKey.writeKeyFile(jfrogHomeTempDir);
            env.putIfAbsent(JFROG_CLI_ENCRYPTION_KEY, keyFilePath);
        }
    }

    @SuppressWarnings("HttpUrlsUsage")
    private static void setupProxy(EnvVars env) {
        JenkinsProxyConfiguration proxyConfiguration = new JenkinsProxyConfiguration();
        if (!proxyConfiguration.isProxyConfigured()) {
            return;
        }

        // Add HTTP or HTTPS protocol according to the port.
        String proxyUrl = proxyConfiguration.port == 443 ? "https://" : "http://"; // nosemgrep: java-insecure-protocol
        if (!StringUtils.isAnyBlank(proxyConfiguration.username, proxyConfiguration.password)) {
            // Add username and password, if provided
            proxyUrl += proxyConfiguration.username + ":" + proxyConfiguration.password + "@";
            excludeProxyEnvFromPublishing(env);
        }
        proxyUrl += proxyConfiguration.host + ":" + proxyConfiguration.port;
        env.put(HTTP_PROXY_ENV, proxyUrl);
        env.put(HTTPS_PROXY_ENV, proxyUrl);
        if (StringUtils.isNotBlank(proxyConfiguration.noProxy)) {
            env.put(NO_PROXY, createNoProxyValue(proxyConfiguration.noProxy));
        }
    }

    /**
     * Exclude the HTTP_PROXY and HTTPS_PROXY environment variable from build-info if they contain credentials.
     *
     * @param env - Job's environment variables
     */
    private static void excludeProxyEnvFromPublishing(EnvVars env) {
        String jfrogCliEnvExclude = env.getOrDefault(JFROG_CLI_ENV_EXCLUDE, JFROG_CLI_DEFAULT_EXCLUSIONS);
        env.put(JFROG_CLI_ENV_EXCLUDE, String.join(";", jfrogCliEnvExclude, HTTP_PROXY_ENV, HTTPS_PROXY_ENV));
    }

    /**
     * Converts a list of No Proxy Hosts received by Jenkins into a comma-separated string format expected by JFrog CLI.
     *
     * @param noProxy - A string representing the list of No Proxy Hosts.
     * @return A comma-separated string of No Proxy Hosts.
     */
    static String createNoProxyValue(String noProxy) {
        // Trim leading and trailing spaces, Replace '|' and ';' with spaces and normalize whitespace
        String noProxyListRemoveSpaceAndPipe = noProxy.trim().replaceAll("[\\s|;]+", ",");
        // Replace multiple commas with a single comma, and remove the last one if present
        return noProxyListRemoveSpaceAndPipe.replaceAll(",+", ",").replaceAll("^,|,$", "");
    }
}
