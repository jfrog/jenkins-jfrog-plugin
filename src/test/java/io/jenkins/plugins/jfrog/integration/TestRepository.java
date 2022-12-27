package io.jenkins.plugins.jfrog.integration;

enum TestRepository {
    LOCAL_REPO("jenkins-jfrog-tests-local-1", RepoType.LOCAL),
    CLI_REMOTE_REPO("jenkins-jfrog-tests-cli-remote", RepoType.REMOTE),
    ;
    enum RepoType {
        LOCAL,
        REMOTE,
        VIRTUAL
    }

    private String repoName;
    private RepoType repoType;

    TestRepository(String repoName, RepoType repoType) {
        this.repoName = repoName;
        this.repoType = repoType;
    }

    public RepoType getRepoType() {
        return repoType;
    }

    public String getRepoName() {
        return repoName;
    }

    @Override
    public String toString() {
        return getRepoName();
    }
}
