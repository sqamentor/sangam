Staging directory for JAR_SOURCE=prebuilt image builds. CI copies mariadb-java-client-<version>.jar (and its
-sources.jar) here with `mvn dependency:copy` before `docker build`. Jars placed here are not committed.
