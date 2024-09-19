package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import io.jenkins.plugins.jfrog.actions.JFrogCliConfigEncryption;
import io.jenkins.plugins.jfrog.configuration.JenkinsProxyConfiguration;
import org.apache.commons.lang3.StringUtils;

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
     * @param jfrogHomeTempDir - Calculated JFrog CLI home dir
     * @param encryptionKey    - Random encryption key to encrypt the CLI config
     */
    static void configureCliEnv(EnvVars env, String jfrogHomeTempDir, JFrogCliConfigEncryption encryptionKey) {
        // Setting Jenkins job name as the default build-info name
        env.putIfAbsent(JFROG_CLI_BUILD_NAME, env.get("JOB_NAME"));
        // Setting Jenkins build number as the default build-info number
        env.putIfAbsent(JFROG_CLI_BUILD_NUMBER, env.get("BUILD_NUMBER"));
        // Setting the specific build URL
        env.putIfAbsent(JFROG_CLI_BUILD_URL, env.get("BUILD_URL"));
        // Set up a temporary Jfrog CLI home directory for a specific run
        env.put(JFROG_CLI_HOME_DIR, jfrogHomeTempDir);
        if (StringUtils.isAllBlank(env.get(HTTP_PROXY_ENV), env.get(HTTPS_PROXY_ENV))) {
            // Set up HTTP/S proxy
            setupProxy(env);
        }
        if (encryptionKey.shouldEncrypt()) {
            // Set up a random encryption key to make sure no raw text secrets are stored in the file system
            env.putIfAbsent(JFROG_CLI_ENCRYPTION_KEY, encryptionKey.getKey());
        }
    }

    @SuppressWarnings("HttpUrlsUsage")
    private static void setupProxy(EnvVars env) {
        JenkinsProxyConfiguration proxyConfiguration = new JenkinsProxyConfiguration();
        if (!proxyConfiguration.isProxyConfigured()) {
            return;
        }

        // Add HTTP or HTTPS protocol according to the port
        String proxyUrl = proxyConfiguration.port == 443 ? "https://" : "http://";
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
     * Converts a list of No Proxy Hosts received by Jenkins into a semicolon-separated string format expected by JFrog CLI.
     *
     * @param noProxy - A string representing the list of No Proxy Hosts.
     * @return A semicolon-separated string of No Proxy Hosts.
     */
    static String createNoProxyValue(String noProxy) {
        // Trim leading and trailing spaces, Replace '|' with spaces and normalize whitespace
        String noProxyListRemoveSpaceAndPipe = noProxy.trim().replaceAll("[\\s|]+", ";");
        // Replace multiple semicolon with a single semicolon, and remove the last one if present
        return noProxyListRemoveSpaceAndPipe.replaceAll(";+", ";").replaceAll("^;|;$", "");
    }
}
