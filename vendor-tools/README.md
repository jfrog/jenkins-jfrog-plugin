# Vendoring `build-info-extractor-maven3`

`generate-resources` runs `vendor-build-info-extractor-maven3.sh`, which stages the extractor
jar in `maven-extractor-lib/`. `PlexusModuleContributor` injects it (plus Jackson/Commons from
the same directory) into the forked Maven JVM.

The extractor remains a compile dependency (transitives excluded) so Jenkins-side code can
reference `BuildInfoRecorder`. Prefer Maven 3.8.x or 3.9.0–3.9.11 until
[build-info#841](https://github.com/jfrog/build-info/issues/841) is fixed.

```
vendor-tools/vendor-build-info-extractor-maven3.sh <version> [output-jar]
```
