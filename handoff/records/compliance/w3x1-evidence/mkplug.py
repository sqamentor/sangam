import os, sys
base = sys.argv[1]
plugins = [
 ("org.apache.maven.plugins","maven-clean-plugin","3.2.0","maven-plugin"),
 ("org.apache.maven.plugins","maven-resources-plugin","3.3.1","maven-plugin"),
 ("org.apache.maven.plugins","maven-compiler-plugin","3.14.0","maven-plugin"),
 ("org.apache.maven.plugins","maven-surefire-plugin","3.5.3","maven-plugin"),
 ("org.apache.maven.plugins","maven-failsafe-plugin","3.5.3","maven-plugin"),
 ("org.apache.maven.surefire","surefire-junit-platform","3.5.3","jar"),
 ("org.apache.maven.plugins","maven-jar-plugin","3.4.2","maven-plugin"),
 ("org.apache.maven.plugins","maven-install-plugin","3.1.2","maven-plugin"),
 ("org.apache.maven.plugins","maven-deploy-plugin","3.1.2","maven-plugin"),
 ("org.apache.maven.plugins","maven-site-plugin","3.12.1","maven-plugin"),
 ("org.apache.maven.plugins","maven-enforcer-plugin","3.6.1","maven-plugin"),
 ("org.apache.maven.plugins","maven-shade-plugin","3.6.0","maven-plugin"),
 ("org.apache.maven.plugins","maven-dependency-plugin","3.8.1","maven-plugin"),
 ("org.springframework.boot","spring-boot-maven-plugin","4.1.2","maven-plugin"),
 ("com.github.spotbugs","spotbugs-maven-plugin","4.10.5.0","maven-plugin"),
 ("com.github.spotbugs","spotbugs","4.10.5","jar"),
 ("com.h3xstream.findsecbugs","findsecbugs-plugin","1.14.0","jar"),
 ("org.jacoco","jacoco-maven-plugin","0.8.16","maven-plugin"),
 ("org.pitest","pitest-maven","1.30.2","maven-plugin"),
 ("org.pitest","pitest-junit5-plugin","1.2.3","jar"),
 ("org.sonarsource.scanner.maven","sonar-maven-plugin","5.8.0.7211","maven-plugin"),
 ("com.google.errorprone","error_prone_core","2.52.0","jar"),
 ("com.uber.nullaway","nullaway","0.14.3","jar"),
 ("org.cyclonedx","cyclonedx-maven-plugin","2.9.1","maven-plugin"),
]
mods = []
for g, a, v, t in plugins:
    m = "p-" + a + "-" + v
    mods.append(m)
    os.makedirs(os.path.join(base, m), exist_ok=True)
    with open(os.path.join(base, m, "pom.xml"), "w") as f:
        f.write(f"""<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<parent><groupId>w3x1</groupId><artifactId>plug-root</artifactId><version>1</version></parent>
<artifactId>{m}</artifactId><packaging>pom</packaging>
<dependencies><dependency><groupId>{g}</groupId><artifactId>{a}</artifactId><version>{v}</version><type>{t}</type></dependency></dependencies>
</project>""")
with open(os.path.join(base, "pom.xml"), "w") as f:
    f.write("""<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>w3x1</groupId><artifactId>plug-root</artifactId><version>1</version><packaging>pom</packaging>
<repositories><repository><id>shibboleth-releases</id><url>https://build.shibboleth.net/maven/releases/</url></repository></repositories>
<modules>""" + "".join(f"<module>{m}</module>" for m in mods) + "</modules></project>")
