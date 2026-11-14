import sys, glob, os
s = sys.argv[1]
os.chdir(sys.argv[2])
raw = open(s + '/server-cp.txt').read().strip().split(';')
cp = [p.replace('\\', '/') for p in raw]
cp = [p for p in cp if '/org/sangam/' not in p]
src = [d for d in glob.glob('*/src/main/java') + glob.glob('tools/*/src/main/java') if os.path.isdir(d)]
src.append('sangam-server/src/test/java')
files = ['sangam-server/src/main/java/org/sangam/server/bridge/ModuleBulkSinks.java',
         'sangam-server/src/main/java/org/sangam/server/config/OpsWiring.java',
         'sangam-server/src/test/java/org/sangam/server/bridge/ModuleBulkSinksTest.java',
         'sangam-server/src/main/java/org/sangam/server/bridge/RegistryPartyBulkSink.java',
         'sangam-server/src/test/java/org/sangam/server/bridge/RegistryPartyBulkSinkH2Test.java']
with open(s + '/javac.args', 'w') as f:
    f.write('-proc:none\n-implicit:none\n-nowarn\n-d\n"' + s.replace('\\', '/') + '/javac-out"\n')
    f.write('-cp\n"' + ';'.join(cp) + '"\n-sourcepath\n"' + ';'.join(p.replace('\\', '/') for p in src) + '"\n')
    f.write('\n'.join(files) + '\n')
print(len(cp), len(src))
