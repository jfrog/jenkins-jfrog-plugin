#!/usr/bin/env bash
# Usage: source e2e/setup-agents.sh
#
# Creates Jenkins JNLP agents for cross-platform testing, starts the Docker
# containers, and waits for them to come online.
#
# Prerequisites:
#   - Jenkins running on localhost:8080 (admin/password)
#   - The jfrog plugin HPI installed in Jenkins
#   - Docker with multi-platform support (buildx)
#
# What it does:
#   1. Enables the TCP slave agent listener (port 50000)
#   2. Creates two permanent agents: agent-linux-amd64, agent-linux-arm64
#   3. Configures the "releases-jfrog-cli" tool (Install from releases.jfrog.io)
#   4. Starts Docker containers that connect back to Jenkins
#   5. Creates a cross-platform test pipeline
#
# After running, trigger the test with:
#   curl -X POST http://localhost:8080/job/test-cross-platform/build -u admin:password

set -euo pipefail

JENKINS_URL="${JENKINS_URL:-http://localhost:8080}"
JENKINS_USER="${JENKINS_USER:-admin}"
JENKINS_PASS="${JENKINS_PASS:-password}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

get_crumb() {
  local cookies="$1"
  curl -sf -c "$cookies" "${JENKINS_URL}/crumbIssuer/api/json" \
    -u "${JENKINS_USER}:${JENKINS_PASS}" | python3 -c "import json,sys; print(json.load(sys.stdin)['crumb'])"
}

run_groovy() {
  local script="$1"
  local cookies
  cookies=$(mktemp)
  local crumb
  crumb=$(get_crumb "$cookies")
  curl -sf -X POST "${JENKINS_URL}/scriptText" \
    -u "${JENKINS_USER}:${JENKINS_PASS}" \
    -b "$cookies" \
    -H "Jenkins-Crumb: ${crumb}" \
    --data-urlencode "script=${script}"
  rm -f "$cookies"
}

echo "==> Checking Jenkins is up..."
if ! curl -sf "${JENKINS_URL}/api/json" -u "${JENKINS_USER}:${JENKINS_PASS}" > /dev/null 2>&1; then
  echo "ERROR: Jenkins not reachable at ${JENKINS_URL}"
  return 1
fi

echo "==> Enabling TCP slave agent listener on port 50000..."
run_groovy '
import jenkins.model.Jenkins
def jenkins = Jenkins.instance
jenkins.setSlaveAgentPort(50000)
jenkins.save()
println "TCP listener enabled on port: " + jenkins.getSlaveAgentPort()
'

echo ""
echo "==> Creating agents and retrieving secrets..."
SECRETS=$(run_groovy '
import jenkins.model.*
import hudson.model.*
import hudson.slaves.*

def createAgent(String name, String label) {
    def jenkins = Jenkins.instance
    if (jenkins.getNode(name) != null) {
        jenkins.removeNode(jenkins.getNode(name))
    }
    def launcher = new JNLPLauncher(true)
    def agent = new DumbSlave(name, "/home/jenkins/agent", launcher)
    agent.setLabelString(label)
    agent.setRetentionStrategy(RetentionStrategy.INSTANCE)
    jenkins.addNode(agent)
}

createAgent("agent-linux-amd64", "linux-amd64")
createAgent("agent-linux-arm64", "linux-arm64")

Jenkins.instance.nodes.each { node ->
    if (node.name.startsWith("agent-linux")) {
        def computer = node.toComputer()
        if (computer instanceof hudson.slaves.SlaveComputer) {
            println "SECRET_${node.name.replace("-", "_").toUpperCase()}=${computer.getJnlpMac()}"
        }
    }
}
')

echo "$SECRETS"

# Parse secrets
export AGENT_AMD64_SECRET=$(echo "$SECRETS" | grep "SECRET_AGENT_LINUX_AMD64" | cut -d= -f2)
export AGENT_ARM64_SECRET=$(echo "$SECRETS" | grep "SECRET_AGENT_LINUX_ARM64" | cut -d= -f2)

if [ -z "$AGENT_AMD64_SECRET" ] || [ -z "$AGENT_ARM64_SECRET" ]; then
  echo "ERROR: Failed to retrieve agent secrets"
  return 1
fi

echo ""
echo "==> Pulling agent images (both platforms)..."
docker pull --platform linux/amd64 jenkins/inbound-agent:latest
docker pull --platform linux/arm64 jenkins/inbound-agent:latest

echo ""
echo "==> Starting agent containers..."
docker compose -f "$SCRIPT_DIR/docker-compose-agents.yml" up -d

echo ""
echo "==> Waiting for agents to come online..."
for i in $(seq 1 30); do
  ONLINE_COUNT=$(run_groovy '
    def count = Jenkins.instance.nodes.count { node ->
      node.name.startsWith("agent-linux") && node.toComputer()?.isOnline()
    }
    print count
  ')
  if [ "$ONLINE_COUNT" = "2" ]; then
    echo "==> Both agents are online!"
    break
  fi
  echo "  ...waiting for agents ($ONLINE_COUNT/2 online, attempt $i/30)"
  sleep 5
done

echo ""
echo "==> Configuring JFrog CLI tool..."
run_groovy '
import jenkins.model.*
import hudson.tools.*
import io.jenkins.plugins.jfrog.*

def jenkins = Jenkins.instance
def desc = jenkins.getDescriptorByType(JfrogInstallation.DescriptorImpl.class)
def existing = desc.getInstallations()?.toList() ?: []

if (!existing.any { it.name == "releases-jfrog-cli" }) {
    def installer = new ReleasesInstaller()
    def props = new DescribableList(Saveable.NOOP)
    props.add(new InstallSourceProperty([installer]))
    def tool = new JfrogInstallation("releases-jfrog-cli", "", props)
    existing.add(tool)
    desc.setInstallations(existing.toArray(new JfrogInstallation[0]))
    println "Tool releases-jfrog-cli configured"
} else {
    println "Tool releases-jfrog-cli already exists"
}
'

echo ""
echo "==> Creating cross-platform test pipeline..."
COOKIES=$(mktemp)
CRUMB=$(get_crumb "$COOKIES")

# Delete if exists
curl -sf -X POST "${JENKINS_URL}/job/test-cross-platform/doDelete" \
  -u "${JENKINS_USER}:${JENKINS_PASS}" -b "$COOKIES" -H "Jenkins-Crumb: ${CRUMB}" 2>/dev/null || true

CRUMB=$(get_crumb "$COOKIES")
curl -sf -X POST "${JENKINS_URL}/createItem?name=test-cross-platform" \
  -u "${JENKINS_USER}:${JENKINS_PASS}" -b "$COOKIES" -H "Jenkins-Crumb: ${CRUMB}" \
  -H "Content-Type: application/xml" \
  --data-binary @- << 'XMLEOF'
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <description>Cross-platform JFrog CLI installation test.
Tests: per-pipeline cache, agent OS detection, multi-arch binary download.</description>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
    <script>
pipeline {
    agent none
    stages {
        stage("Mac/Host Controller") {
            agent { label "built-in" }
            tools { jfrog "releases-jfrog-cli" }
            steps {
                echo "Running on controller"
                sh "uname -m &amp;&amp; uname -s"
            }
        }
        stage("Mac/Host Controller Again") {
            agent { label "built-in" }
            tools { jfrog "releases-jfrog-cli" }
            steps {
                echo "Same agent - expect cache hit, no BinaryInstaller messages"
                sh "uname -m"
            }
        }
        stage("Linux AMD64") {
            agent { label "linux-amd64" }
            tools { jfrog "releases-jfrog-cli" }
            steps {
                echo "Running on Linux AMD64 agent"
                sh "uname -m &amp;&amp; uname -s"
                sh "/home/jenkins/agent/tools/io.jenkins.plugins.jfrog.JfrogInstallation/releases-jfrog-cli/jf --version"
            }
        }
        stage("Linux ARM64") {
            agent { label "linux-arm64" }
            tools { jfrog "releases-jfrog-cli" }
            steps {
                echo "Running on Linux ARM64 agent"
                sh "uname -m &amp;&amp; uname -s"
                sh "/home/jenkins/agent/tools/io.jenkins.plugins.jfrog.JfrogInstallation/releases-jfrog-cli/jf --version"
            }
        }
        stage("Linux AMD64 Again") {
            agent { label "linux-amd64" }
            tools { jfrog "releases-jfrog-cli" }
            steps {
                echo "Same AMD64 agent - expect cache hit"
                sh "uname -m"
            }
        }
        stage("Linux ARM64 Again") {
            agent { label "linux-arm64" }
            tools { jfrog "releases-jfrog-cli" }
            steps {
                echo "Same ARM64 agent - expect cache hit"
                sh "uname -m"
            }
        }
    }
}
    </script>
    <sandbox>true</sandbox>
  </definition>
</flow-definition>
XMLEOF

rm -f "$COOKIES"

echo ""
echo "==> Setup complete!"
echo ""
echo "Agents:"
run_groovy '
Jenkins.instance.nodes.each { node ->
    def c = node.toComputer()
    println "  ${node.name}: online=${c?.isOnline()}, label=${node.labelString}"
}
'
echo ""
echo "To run the cross-platform test:"
echo "  curl -X POST ${JENKINS_URL}/job/test-cross-platform/build -u ${JENKINS_USER}:${JENKINS_PASS}"
echo ""
echo "To view results:"
echo "  curl ${JENKINS_URL}/job/test-cross-platform/lastBuild/consoleText -u ${JENKINS_USER}:${JENKINS_PASS}"
echo ""
echo "To enable FINE logging for debug:"
echo "  Manage Jenkins → System Log → Add 'io.jenkins.plugins.jfrog.BinaryInstaller' at FINE"
echo ""
echo "To tear down agents:"
echo "  docker compose -f e2e/local/docker-compose-agents.yml down"
