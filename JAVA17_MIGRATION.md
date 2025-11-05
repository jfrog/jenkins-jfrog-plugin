# Java 17 Migration

## Overview

This document explains the migration from Java 11 to Java 17 as the minimum required version for the JFrog Jenkins Plugin.

## Why We Moved to Java 17

### Security Vulnerability Fixes

The primary driver for this migration was the need to address security vulnerabilities detected in our dependencies. The security audit identified several critical vulnerabilities that required updating to newer versions of Jenkins and related dependencies.

### Dependency Requirements

The migration was necessitated by the following dependency updates made in commit `da8c993` (November 5, 2025):

1. **Jenkins Core Version Update**: 
   - From: `2.462.3`
   - To: `2.528.1`
   - This update was required to patch security vulnerabilities

2. **Jenkins Plugin Parent**: 
   - Version: `4.88`
   - This parent version requires Java 17 as the minimum runtime

3. **Dependency Security Updates**:
   - Updated multiple Spring Security components to `5.8.16`
   - Updated Apache Commons Lang3 to `3.18.0`
   - Updated Jackson libraries to `2.17.2`
   - Updated Mockito to `5.14.2`
   - Added explicit dependency management to resolve version conflicts

### Technical Benefits of Java 17

1. **Long Term Support (LTS)**: Java 17 is a Long Term Support release, providing stability and long-term support until 2029.

2. **Performance Improvements**: Java 17 includes significant performance enhancements over Java 11, including:
   - Better garbage collection algorithms
   - Improved JIT compiler optimizations
   - Enhanced startup time

3. **Security Enhancements**: Java 17 includes numerous security improvements and patches that are not available in Java 11.

4. **Modern Language Features**: Access to newer Java language features and APIs introduced between Java 11 and 17.

## Impact Analysis

### Build Environment Changes

The following GitHub Actions workflows were updated to use Java 17:

- `.github/workflows/tests.yml`
- `.github/workflows/analysis.yml` 
- `.github/workflows/frogbot.yml`

### Compatibility

- **Jenkins Compatibility**: The plugin remains compatible with Jenkins `2.528.1` and later
- **Backward Compatibility**: The plugin functionality remains unchanged; only the build/runtime requirements have changed
- **Plugin Dependencies**: All plugin dependencies have been updated to versions compatible with Java 17

### User Impact

- **Plugin Users**: No impact on plugin functionality
- **Plugin Developers**: Must use Java 17 or later for building and testing
- **CI/CD**: All continuous integration pipelines now use Java 17

## Migration Timeline

- **Date**: November 5, 2025
- **Commit**: `da8c993811fedb8dfb09dda882158fc1f4e02194`
- **Reason**: Security vulnerability remediation

## Verification

To verify your environment supports the new requirements:

```bash
# Check Java version
java -version
# Should show Java 17 or later

# Check Maven build
mvn -V -B -U --no-transfer-progress clean verify
# Should complete successfully with Java 17+
```

## Support

If you encounter issues related to the Java 17 migration:

1. Ensure you're using Java 17 or later
2. Update your development environment and CI/CD pipelines
3. Clear Maven local repository if you encounter dependency resolution issues:
   ```bash
   mvn dependency:purge-local-repository
   ```

## References

- [Jenkins Plugin Development - Java Requirements](https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/)
- [Java 17 Migration Guide](https://docs.oracle.com/en/java/javase/17/migrate/getting-started.html)
- [Jenkins Security Advisory Archive](https://www.jenkins.io/security/advisories/)

---

**Note**: This migration ensures the plugin remains secure and maintainable while following Jenkins community best practices for plugin development.