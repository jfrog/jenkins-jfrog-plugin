# 📖 Guidelines

- The plugin code is stored in two GitHub repositories: https://github.com/jfrog/jenkins-jfrog-plugin and https://github.com/jenkinsci/jfrog-plugin. Please make sure to submit pull requests to *https://github.com/jfrog/jenkins-jfrog-plugin* only.
- If the existing tests do not already cover your changes, please add tests.

# ⚒️ Building and Testing the Sources

## Build and Run Jenkins JFrog Plugin

Clone the sources and CD to the root directory of the project:

```sh
git clone https://github.com/jfrog/jenkins-jfrog-plugin.git
cd jenkins-jfrog-plugin
```

Build the sources as follows:

```sh
mvn clean package
```

Start a local Jenkins instance with the plugin installed by running the following command:

```sh
mvn hpi:run
```

## Tests

### Unit tests

To run unit tests execute the following command:

```sh
mvn clean test
```

### Integration tests

#### Running integration tests

Before running the integration tests, set the following environment variables.

_JFROG_URL_<br>
_JFROG_USERNAME_<br>
_JFROG_PASSWORD_<br>
_JFROG_ADMIN_TOKEN_<br>

Run the integration tests.

```sh
mvn clean verify -DskipITs=false
```
