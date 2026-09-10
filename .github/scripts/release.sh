#!/usr/bin/env bash
# Release logic for jenkins-jfrog-plugin, extracted from
# .github/workflows/release.yml so it can be run both from the workflow
# and directly on a developer's machine.
#
# Expects the following environment variables to be set (the workflow sets
# these via the job-level `env:` block; to run locally, export the same
# variables and then invoke this script directly):
#   NEXT_VERSION                  - version to release (e.g. 1.9.0)
#   NEXT_DEVELOPMENT_VERSION      - next development version (e.g. 1.9.x-SNAPSHOT)
#   IL_AUTOMATION_TOKEN           - GitHub token used to push to jfrog/jenkins-jfrog-plugin and jenkinsci/jfrog-plugin
#   JENKINS_ARTIFACTORY_URL       - Jenkins Update Center Artifactory URL
#   JENKINS_ARTIFACTORY_USER      - Jenkins Update Center Artifactory user
#   JENKINS_ARTIFACTORY_PASSWORD  - Jenkins Update Center Artifactory password
#
# The "internal" Artifactory server (ecosys_entplus_deployer) is NOT configured
# here -- the workflow's "Setup JFrog CLI" step auto-configures it as the
# default server (custom-server-id: internal) via JF_URL/JF_USER/JF_PASSWORD.
set -euo pipefail

# Configure git
git config user.name "jfrog-ecosystem-integration"
git config user.email "eco-system@jfrog.com"
git remote set-url origin https://${IL_AUTOMATION_TOKEN}@github.com/jfrog/jenkins-jfrog-plugin.git
git remote add upstream https://${IL_AUTOMATION_TOKEN}@github.com/jenkinsci/jfrog-plugin.git

# Configure repo resolution/deployment rules against the "internal" server
jf mvnc --repo-resolve-releases ecosys-jenkins-repos --repo-resolve-snapshots ecosys-releases-snapshots --repo-deploy-snapshots ecosys-oss-snapshot-local --repo-deploy-releases ecosys-oss-release-local

# Audit
jf audit --fail=false

# Set release version
jf mvn versions:set -DnewVersion="${NEXT_VERSION}" -B
git commit -am "[jfrog-release] Release version ${NEXT_VERSION} [skipRun]" --allow-empty
git tag jfrog-${NEXT_VERSION}

# Build and publish (build info is collected and auto-published by
# jfrog/setup-jfrog-cli's post-job step -- no explicit jf rt bag/bce/bp needed)
jf mvn clean install -U -B -Denforcer.skip -DskipTests

# Distribute release bundle
jf ds rbc ecosystem-jfrog-jenkins-plugin ${NEXT_VERSION} --spec=./.jfrog-pipelines/specs/prod-rbc-filespec.json --spec-vars="version=${NEXT_VERSION}" --sign
jf ds rbd ecosystem-jfrog-jenkins-plugin ${NEXT_VERSION} --site="releases.jfrog.io" --sync

# Publish to Jenkins Update Center
jf c add jenkins --url=${JENKINS_ARTIFACTORY_URL} --user=${JENKINS_ARTIFACTORY_USER} --password=${JENKINS_ARTIFACTORY_PASSWORD} --enc-password=false
jf mvnc --server-id-resolve internal --repo-resolve-releases ecosys-jenkins-repos --repo-resolve-snapshots ecosys-releases-snapshots --server-id-deploy jenkins --repo-deploy-releases releases --repo-deploy-snapshots snapshots
jf mvn clean install -U -B

# Set next development version
jf mvn versions:set -DnewVersion="${NEXT_DEVELOPMENT_VERSION}" -B
git commit -am "[jfrog-release] Next development version [skipRun]"

# Push changes
git push origin main
git push origin --tags
git push upstream main
git push upstream main --tags
