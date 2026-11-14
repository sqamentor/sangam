import re, sys
src_dir = r"D:\GITHUB  Project\Custom WorkFlow\spike\experiments-gov-a\src\test\java\org\sangam\spike\gova"
dst_dir = r"D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-server\src\test\java\org\sangam\server\acceptance\gov"

HEADER = """// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;
"""

def port(name, newname, extra_imports, doc):
    s = open(src_dir + "\\" + name + ".java", encoding="utf-8").read()
    s = re.sub(r"^package [^\n]*\n", "", s)
    # drop spike imports
    s = re.sub(r"^import org\.sangam\.spike\.[^\n]*\n", "", s, flags=re.M)
    s = re.sub(r"^import org\.junit\.jupiter\.api\.AfterAll;\n", "", s, flags=re.M)
    imports = "\n".join("import " + i + ";" for i in extra_imports) + "\n"
    s = s.replace("import org.junit.jupiter.api.MethodOrderer;", imports + "import org.junit.jupiter.api.MethodOrderer;")
    # class declaration and results plumbing
    s = re.sub(r"/\*\* E1[67]-a:[^\n]*\*/\n", doc, s)
    s = re.sub(r"class \w+ extends GovBase \{", "class " + newname + " extends GovABase {", s)
    s = re.sub(r"\n    private static final Results RESULTS.*?\n    @AfterAll\n    static void write\(\) \{.*?\n    \}\n", "\n", s, flags=re.S)
    s = s.replace("org.sangam.spike.gova.support.Rec.errorText", "GovARec.errorText")
    s = s.replace("org.sangam.spike.api.EngineAdapter", "org.sangam.core.api.EngineAdapter")
    s = re.sub(r"\bRec\b", "GovARec", s)
    s = re.sub(r"\bBizCal\b", "GovACal", s)
    s = re.sub(r"\bIfmsClient\b", "GovAIfmsClient", s)
    s = s.replace("Db.engineTableRows(db)", "engineTableRows()")
    s = s.replace('Files.createTempDirectory(Path.of("target"), "pfms-")', 'Files.createTempDirectory("pfms-")')
    open(dst_dir + "\\" + newname + ".java", "w", encoding="utf-8").write(HEADER + s)

port("E16PatternsTest", "E16aPatternsIT", [
    "org.sangam.core.api.EngineAdapter.TaskQuery",
    "org.sangam.core.api.EngineAdapter.TaskView",
    "org.sangam.integration.sim.Fault",
    "org.sangam.integration.sim.ifms.IfmsJitSimulator"],
    "/**\n * E16-a ported to the Sangam server (spike experiments-gov-a E16PatternsTest, Operaton side): government patterns\n"
    " * GP-01..GP-20, same scenarios and checks, run through Sangam's engine adapter bean (see {@link GovABase}).\n */\n")
port("E17DomainsTest", "E17aDomainsIT", [
    "org.sangam.core.api.EngineAdapter.ExternalWork",
    "org.sangam.core.api.EngineAdapter.TaskView",
    "org.sangam.integration.connector.file.LocalDirectoryTransport",
    "org.sangam.integration.sim.ifms.IfmsJitSimulator",
    "org.sangam.integration.sim.pfms.PfmsFiles",
    "org.sangam.integration.sim.pfms.PfmsSftpSimulator"],
    "/**\n * E17-a ported to the Sangam server (spike experiments-gov-a E17DomainsTest, Operaton side): domain end-to-end\n"
    " * models, same scenarios and checks, with Sangam's own IFMS JIT and PFMS SFTP simulators (sangam-integration).\n */\n")
print("ok")
