Put customer-supplied JDBC drivers here (mounted read-only on /opt/sangam/drivers). See SANGAM.md X.3a.
  --profile oracle : ojdbc11.jar (or ojdbc17.jar) is REQUIRED; Sangam does not ship it (LIC-2).
  other profiles   : nothing needed (PostgreSQL and SQL Server drivers are bundled, MariaDB Connector/J
                     in /opt/sangam/lib-ext serves MySQL 8.4).
Jars in this directory are git-ignored by convention; never commit vendor drivers.
