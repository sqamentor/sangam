import io, sys
R = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"

def rw(path):
    s = io.open(path, encoding="utf-8", newline="").read()
    return s, ("\r\n" if "\r\n" in s else "\n")

def patch(path, old, new):
    s, nl = rw(path)
    old = old.replace("\n", nl); new = new.replace("\n", nl)
    assert s.count(old) == 1, (path, old[:80], s.count(old))
    io.open(path, "w", encoding="utf-8", newline="").write(s.replace(old, new))

root = R + r"\pom.xml"
patch(root, "    <module>sdks/java</module>\n",
      "    <module>sdks/java</module>\n    <!-- M-027: Spring Boot worker adapter (an SDK host, decision 74) -->\n    <module>sdks/java-spring-boot</module>\n")
patch(root, "    <version.xmpcore>6.1.11</version.xmpcore>\n",
      "    <version.xmpcore>6.1.11</version.xmpcore>\n"
      "    <!-- M-081: PDF/A-2b + PDF/UA-1 renderer, Apache-2.0 (decision 56) -->\n"
      "    <version.pdfbox>3.0.8</version.pdfbox>\n"
      "    <!-- M-076: OpenSAML line declared by spring-security-saml2-service-provider 7.1.2 (Apache-2.0; shib 9.2.3 Apache-2.0;\n"
      "         cryptacular 1.3.0 dual Apache-2.0 OR LGPL-3.0, Apache-2.0 elected, owner decision 90). Resolved from the\n"
      "         Shibboleth repository below (decision 56). -->\n"
      "    <opensaml.version>5.2.3</opensaml.version>\n")
patch(root, "        <version>${version.xmpcore}</version>\n      </dependency>\n",
      "        <version>${version.xmpcore}</version>\n      </dependency>\n"
      "      <dependency>\n        <groupId>org.apache.pdfbox</groupId>\n        <artifactId>pdfbox</artifactId>\n        <version>${version.pdfbox}</version>\n      </dependency>\n"
      "      <dependency>\n        <groupId>org.opensaml</groupId>\n        <artifactId>opensaml-saml-api</artifactId>\n        <version>${opensaml.version}</version>\n      </dependency>\n"
      "      <dependency>\n        <groupId>org.opensaml</groupId>\n        <artifactId>opensaml-saml-impl</artifactId>\n        <version>${opensaml.version}</version>\n      </dependency>\n")
patch(root, "  </dependencyManagement>\n",
      "  </dependencyManagement>\n\n"
      "  <!-- M-076 (decision 56): OpenSAML is published only on the Shibboleth repository, used for org.opensaml / net.shibboleth\n"
      "       artifacts only; Maven Central stays the default for everything else. -->\n"
      "  <repositories>\n    <repository>\n      <id>shibboleth-releases</id>\n      <url>https://build.shibboleth.net/maven/releases/</url>\n"
      "      <releases><enabled>true</enabled></releases>\n      <snapshots><enabled>false</enabled></snapshots>\n    </repository>\n  </repositories>\n")

srv = R + r"\sangam-server\pom.xml"
patch(srv, "      <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>\n    </dependency>\n",
      "      <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>\n    </dependency>\n"
      "    <!-- M-076 SAML relying party, host side only (decision 73); off unless sangam.security.saml.enabled. Version from\n"
      "         the Spring Boot BOM; OpenSAML pinned in the root pom. -->\n"
      "    <dependency>\n      <groupId>org.springframework.security</groupId>\n      <artifactId>spring-security-saml2-service-provider</artifactId>\n    </dependency>\n"
      "    <!-- M-081: lets the PDFBox renderer in sangam-integration load at runtime (it is optional there) -->\n"
      "    <dependency>\n      <groupId>org.apache.pdfbox</groupId>\n      <artifactId>pdfbox</artifactId>\n    </dependency>\n")

pol = R + r"\deploy\licence\licence-policy.json"
patch(pol, "Any other OFL font needs a new owner decision.\"\n    }\n  ],",
      "Any other OFL font needs a new owner decision.\"\n    },\n"
      "    {\n      \"license\": \"LGPL-3.0\",\n      \"components\": [\"pkg:maven/org.cryptacular/cryptacular@1.3.0\"],\n"
      "      \"condition\": \"Owner decision 90 (2026-11-12): cryptacular 1.3.0 is dual-licensed Apache-2.0 OR LGPL-3.0; Sangam elects Apache-2.0. Reached only through the optional M-076 SAML relying party (OpenSAML 5.2.3). Exactly this version; any other version is re-approved.\"\n    }\n  ],")
print("ok")
