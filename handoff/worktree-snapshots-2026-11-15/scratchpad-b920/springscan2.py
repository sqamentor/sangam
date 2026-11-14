import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for needle in (b"BeansConfigurationHelper", b"SpringBeanFactoryProxyMap"):
    print(needle.decode(), [n for n in z.namelist() if n.endswith(".class") and needle in z.read(n)])
