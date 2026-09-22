#!/usr/bin/env bash
# Re-vendors org.jfrog.buildinfo:build-info-extractor-maven3 into
# io.jenkins.plugins.jfrog.vendored:build-info-extractor-maven3-filtered, stripping the
# "Artifactory-backed dependency resolution" classes that are incompatible with Maven 3.9.x.
#
# See pom.xml (search for "build-info-extractor-maven3-filtered") and README.md in this
# directory for the full explanation of why this exists.
#
# Run this after bumping the `buildinfo.version` property in pom.xml, then update that
# property and rebuild.
#
# Usage:
#   vendor-tools/vendor-build-info-extractor-maven3.sh <buildinfo.version>
#   vendor-tools/vendor-build-info-extractor-maven3.sh <buildinfo.version> <output-jar-path>
#
# The optional output path writes the filtered jar there and skips install-file.
# The Maven generate-resources step uses that form so CI does not need a pre-installed artifact.
set -euo pipefail

VERSION="${1:?Usage: $0 <buildinfo.version> [output-jar-path]}"
OUTPUT_JAR="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

GROUP_PATH="org/jfrog/buildinfo/build-info-extractor-maven3"
ORIGINAL_JAR="$HOME/.m2/repository/${GROUP_PATH}/${VERSION}/build-info-extractor-maven3-${VERSION}.jar"

if [ ! -f "$ORIGINAL_JAR" ]; then
    echo "Resolving org.jfrog.buildinfo:build-info-extractor-maven3:${VERSION} via Maven..." >&2
    mvn -q dependency:get -Dartifact="org.jfrog.buildinfo:build-info-extractor-maven3:${VERSION}"
fi

if [ ! -f "$ORIGINAL_JAR" ]; then
    echo "ERROR: could not find or resolve $ORIGINAL_JAR" >&2
    exit 1
fi

echo "Extracting $ORIGINAL_JAR ..." >&2
EXTRACT_DIR="$WORK_DIR/extracted"
mkdir -p "$EXTRACT_DIR"
(cd "$EXTRACT_DIR" && unzip -q "$ORIGINAL_JAR")

echo "Removing Artifactory-backed dependency-resolution classes (incompatible with Maven 3.9.x)..." >&2
cd "$EXTRACT_DIR"
rm -fv org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseResolversHelper*.class
rm -fv org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipsePluginManager*.class
rm -fv org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseMetadataResolver*.class
rm -fv org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseArtifactResolver*.class
rm -fv org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseRepositoryListener*.class
rm -fv org/jfrog/build/extractor/maven/ArtifactoryProjectBuilder*.class
cd - > /dev/null

echo "Filtering META-INF/plexus/components.xml ..." >&2
python3 "$SCRIPT_DIR/filter_components.py" \
    "$EXTRACT_DIR/META-INF/plexus/components.xml" \
    "$EXTRACT_DIR/META-INF/plexus/components.xml.filtered"
mv "$EXTRACT_DIR/META-INF/plexus/components.xml.filtered" "$EXTRACT_DIR/META-INF/plexus/components.xml"

FILTERED_JAR="$WORK_DIR/build-info-extractor-maven3-${VERSION}-filtered.jar"
echo "Repackaging into $FILTERED_JAR ..." >&2
(cd "$EXTRACT_DIR" && zip -r -q "$FILTERED_JAR" .)

if [ -n "${OUTPUT_JAR}" ]; then
    mkdir -p "$(dirname "${OUTPUT_JAR}")"
    cp "${FILTERED_JAR}" "${OUTPUT_JAR}"
    printf 'Wrote filtered jar to %s\n' "${OUTPUT_JAR}" >&2
else
    echo "Installing io.jenkins.plugins.jfrog.vendored:build-info-extractor-maven3-filtered:${VERSION} ..." >&2
    mvn install:install-file \
        -Dfile="$FILTERED_JAR" \
        -DgroupId=io.jenkins.plugins.jfrog.vendored \
        -DartifactId=build-info-extractor-maven3-filtered \
        -Dversion="$VERSION" \
        -Dpackaging=jar
    echo "Done. Set <buildinfo.version>${VERSION}</buildinfo.version> in pom.xml and rebuild." >&2
fi
