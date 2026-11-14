import re
import sys
import zipfile

z = zipfile.ZipFile(sys.argv[1])
t = z.read("META-INF/quarkus-config-doc/quarkus-config-model.json").decode()
print(sorted(set(re.findall(r"quarkus\.operaton\.[a-z.\-]+", t)))[:30])
