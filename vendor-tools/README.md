# Vendoring `build-info-extractor-maven3`

`org.jfrog.buildinfo:build-info-extractor-maven3` contains `BuildInfoRecorder`, the
Maven-native listener this plugin's Maven Project support contributes into Maven's
own process.

That upstream jar also unconditionally registers an optional Artifactory-backed
dependency-resolution feature that overrides Maven 3.9-incompatible components.
This plugin only needs build-info *capture*, so those six classes are stripped
from a copy written to `target/classes/maven-extractor-lib/` during
`generate-resources`.

The official (unfiltered) extractor remains a normal compile dependency so the
project resolves on a clean machine. Jackson / Commons jars for the forked Maven
JVM are copied into the same `maven-extractor-lib/` directory and are **not**
added as Jenkins-side compile dependencies (Pipeline/Freestyle keep using
`jackson2-api` / `commons-lang3-api`).

## Re-vendoring after a `buildinfo.version` bump

```
vendor-tools/vendor-build-info-extractor-maven3.sh <new-version>
```

That still installs a local filtered artifact for manual use. The Maven build
itself calls the same script with an output path:

```
vendor-tools/vendor-build-info-extractor-maven3.sh <version> <output-jar>
```
