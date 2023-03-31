package io.jenkins.plugins.jfrog.configuration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * @author yahavi
 **/
public class JFrogPlatformInstanceTest {

    private static final String DISTRIBUTION_URL = "https://acme.jfrog.io/distribution";
    private static final String ARTIFACTORY_URL = "https://acme.jfrog.io/artifactory";
    private static final String XRAY_URL = "https://acme.jfrog.io/xray";
    private static final String URL = "https://acme.jfrog.io";
    private static final String SERVER_ID = "acme";

    @Test
    public void testInferArtifactoryUrl() {
        // Check that Artifactory URL inferred from the platform URL
        JFrogPlatformInstance jfrogPlatformInstance = new JFrogPlatformInstance(SERVER_ID, URL, null, "", "", "");
        assertEquals("", jfrogPlatformInstance.getArtifactoryUrl());
        assertEquals(ARTIFACTORY_URL, jfrogPlatformInstance.inferArtifactoryUrl());

        // Check that Artifactory URL is not inferred from the platform URL
        jfrogPlatformInstance = new JFrogPlatformInstance(SERVER_ID, "", null, ARTIFACTORY_URL, "", "");
        assertEquals(ARTIFACTORY_URL, jfrogPlatformInstance.getArtifactoryUrl());
        assertEquals(ARTIFACTORY_URL, jfrogPlatformInstance.inferArtifactoryUrl());
    }

    @Test
    public void testInferDistributionUrl() {
        // Check that Distribution URL inferred from the platform URL
        JFrogPlatformInstance jfrogPlatformInstance = new JFrogPlatformInstance(SERVER_ID, URL, null, "", "", "");
        assertEquals("", jfrogPlatformInstance.getDistributionUrl());
        assertEquals(DISTRIBUTION_URL, jfrogPlatformInstance.inferDistributionUrl());

        // Check that Distribution URL is not inferred from the platform URL
        jfrogPlatformInstance = new JFrogPlatformInstance(SERVER_ID, "", null, "", DISTRIBUTION_URL, "");
        assertEquals(DISTRIBUTION_URL, jfrogPlatformInstance.getDistributionUrl());
        assertEquals(DISTRIBUTION_URL, jfrogPlatformInstance.inferDistributionUrl());
    }

    @Test
    public void testInferXrayUrl() {
        // Check that Xray URL inferred from the platform URL
        JFrogPlatformInstance jfrogPlatformInstance = new JFrogPlatformInstance(SERVER_ID, URL, null, "", "", "");
        assertEquals("", jfrogPlatformInstance.getXrayUrl());
        assertEquals(XRAY_URL, jfrogPlatformInstance.inferXrayUrl());

        // Check that Xray URL is not inferred from the platform URL
        jfrogPlatformInstance = new JFrogPlatformInstance(SERVER_ID, "", null, "", "", XRAY_URL);
        assertEquals(XRAY_URL, jfrogPlatformInstance.getXrayUrl());
        assertEquals(XRAY_URL, jfrogPlatformInstance.inferXrayUrl());
    }
}
