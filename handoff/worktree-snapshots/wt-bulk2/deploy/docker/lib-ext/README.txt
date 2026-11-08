Staging directory for JAR_SOURCE=prebuilt image builds. CI copies mariadb-java-client-<version>.jar (and its
-sources.jar) here with `mvn dependency:copy` before `docker build`. Jars placed here are not committed.
This directory is for the shipped MariaDB jar only. Customer drivers (Oracle ojdbc11, Db2) go in
/opt/sangam/drivers at run time, never here and never in the image: see sangam/deploy/README.md section 1.5.
