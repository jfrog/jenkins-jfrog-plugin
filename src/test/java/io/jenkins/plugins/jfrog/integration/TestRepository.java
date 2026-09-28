package io.jenkins.plugins.jfrog.integration;

enum TestRepository {
    LOCAL_REPO("jenkins-jfrog-tests-local", RepoType.LOCAL),
    MAVEN_LOCAL_REPO("jenkins-jfrog-tests-maven-local", RepoType.LOCAL),
    MAVEN_SNAPSHOT_REPO("jenkins-jfrog-tests-maven-snapshot", RepoType.LOCAL),
    // Proxies Maven Central. MAVEN_LOCAL_REPO alone has no upstream, so once Resolve Repository
    // is configured against it, it hijacks ALL Maven resolution (including core lifecycle plugins
    // like maven-clean-plugin) with nowhere to fall back to. MAVEN_VIRTUAL_REPO aggregates this
    // with MAVEN_LOCAL_REPO so the resolver test can find both a custom seeded dependency and
    // standard plugins. Declared, and therefore created, before MAVEN_VIRTUAL_REPO.
    MAVEN_REMOTE_REPO("jenkins-jfrog-tests-maven-remote", RepoType.REMOTE),
    MAVEN_VIRTUAL_REPO("jenkins-jfrog-tests-maven-virtual", RepoType.VIRTUAL),
    CLI_REMOTE_REPO("jenkins-jfrog-tests-cli-remote", RepoType.REMOTE);

    enum RepoType {
        LOCAL,
        REMOTE,
        VIRTUAL
    }

    private final String repoName;
    private final RepoType repoType;

    TestRepository(String repoName, RepoType repoType) {
        this.repoName = repoName;
        this.repoType = repoType;
    }

    public String getRepoName() {
        return repoName;
    }

    public RepoType getRepoType() {
        return repoType;
    }

    @Override
    public String toString() {
        return getRepoName();
    }
}
