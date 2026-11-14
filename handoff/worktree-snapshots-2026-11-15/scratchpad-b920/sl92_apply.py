import hashlib
import os

I = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
W = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sl92\sangam"


def edit(rel, pairs):
    p = os.path.join(I, rel)
    s = open(p, encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in s else "\n"
    for old, new in pairs:
        old = old.replace("\n", nl)
        new = new.replace("\n", nl)
        assert s.count(old) == 1, (rel, old)
        s = s.replace(old, new)
    open(p, "w", encoding="utf-8", newline="").write(s)


edit(r"sangam-resources\src\main\java\org\sangam\resources\support\Sql.java", [
("""import com.fasterxml.jackson.databind.SerializationFeature;
""", """import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
"""),
("""    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .findAndRegisterModules();
""", """    /**
     * Modules are registered explicitly (SL-92), never discovered from the class path: discovery also registers the
     * FEEL engine's jackson-module-scala, which reads every nested JSON object as a Scala {@code Map}, so
     * {@link #asMap} returned an empty map and nested settings ({@code skillRouting}, {@code priorityLevels},
     * {@code seriesBySkill}, {@code slaMinutesByClass}) were silently ignored.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());
"""),
])

edit(r"sangam-flowable-ports\src\main\java\org\sangam\ports\support\Json.java", [
("""import com.fasterxml.jackson.databind.node.ObjectNode;
""", """import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
"""),
(""" * {@value #MAX_STRING_CHARS} chars, numbers {@value #MAX_NUMBER_CHARS} digits, names {@value #MAX_NAME_CHARS} chars.
 */""", """ * {@value #MAX_STRING_CHARS} chars, numbers {@value #MAX_NUMBER_CHARS} digits, names {@value #MAX_NAME_CHARS} chars.
 *
 * <p>SL-92: modules are registered explicitly (java.time, Optional), never discovered from the class path; discovery
 * also registers the FEEL engine's jackson-module-scala, which turns untyped nested JSON into Scala collections.
 */"""),
("""            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .findAndRegisterModules();
""", """            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());
"""),
])

edit(r"sangam-server\src\main\java\org\sangam\server\bridge\ComplianceAuthorityGateway.java", [
("""import com.fasterxml.jackson.databind.SerializationFeature;
""", """import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
"""),
("""    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();
""", """    /** SL-92: modules registered explicitly, never discovered from the class path (that also adds jackson-module-scala). */
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());
"""),
])

dep = """    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
"""
mods = """    <!-- SL-92: the mapper registers these explicitly (no class-path module discovery). Apache-2.0, Jackson BOM line. -->
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jdk8</artifactId>
    </dependency>
"""
for p in [r"sangam-resources\pom.xml", r"sangam-flowable-ports\pom.xml"]:
    edit(p, [(dep, dep + mods)])
edit(r"sangam-server\pom.xml", [("""      <artifactId>sangam-cell</artifactId>
    </dependency>
""", """      <artifactId>sangam-cell</artifactId>
    </dependency>
""" + mods.replace("the mapper registers", "ComplianceAuthorityGateway registers"))])

NEW = [r"sangam-core\src\test\java\org\sangam\core\json\JacksonModuleDiscoveryGuardTest.java",
       r"sangam-resources\src\test\java\org\sangam\resources\support\SqlJsonTest.java",
       r"sangam-resources\src\test\java\org\sangam\resources\queue\QueueNestedSettingsH2Test.java",
       r"sangam-flowable-ports\src\test\java\org\sangam\ports\support\JsonModulesTest.java"]
for rel in NEW:
    dst = os.path.join(I, rel)
    assert not os.path.exists(dst), rel
    s = open(os.path.join(W, rel), encoding="utf-8", newline="").read().replace("\r\n", "\n").replace("\n", "\r\n")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "w", encoding="utf-8", newline="").write(s)

MOD = [r"sangam-resources\src\main\java\org\sangam\resources\support\Sql.java",
       r"sangam-flowable-ports\src\main\java\org\sangam\ports\support\Json.java",
       r"sangam-server\src\main\java\org\sangam\server\bridge\ComplianceAuthorityGateway.java",
       r"sangam-resources\pom.xml", r"sangam-flowable-ports\pom.xml", r"sangam-server\pom.xml"]
for rel in MOD + NEW:
    ib = open(os.path.join(I, rel), "rb").read()
    wb = open(os.path.join(W, rel), "rb").read()
    same = ib.replace(b"\r\n", b"\n") == wb.replace(b"\r\n", b"\n")
    crlf = b"\r\n" in ib and b"\n" not in ib.replace(b"\r\n", b"")
    print(hashlib.sha256(ib).hexdigest(), "CRLF" if crlf else "MIXED/LF", "matches-wt" if same else "DIFFERS", "sangam\\" + rel)
