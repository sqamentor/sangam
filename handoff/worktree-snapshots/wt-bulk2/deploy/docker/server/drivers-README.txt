Bring-your-own JDBC driver directory (SANGAM.md X.3a LIC-2, LIC-3, LIC-4)

Sangam does not redistribute the Oracle JDBC driver, the IBM Db2 JCC driver or MySQL Connector/J.
Every jar in this directory is added to the server class path at start-up (Spring Boot loader.path).

  Oracle 19c/21c/23ai : ojdbc11.jar (or ojdbc17.jar) from Oracle (Oracle Free Use Terms and Conditions)
  IBM Db2 (Tier 2)    : jcc-<version>.jar from IBM
  MySQL 8.4           : not needed - the shipped MariaDB Connector/J (/opt/sangam/lib-ext) serves MySQL

Docker / compose : mount a host directory on /opt/sangam/drivers (read-only).
Kubernetes (Helm): drivers.existingClaim, or drivers.initImage (an image you build that holds the jar).
Exact install steps (tested version com.oracle.database.jdbc:ojdbc11:23.8.0.25.04, compose, docker run,
plain JVM, Helm PVC/init image): sangam/deploy/README.md section 1.5.
