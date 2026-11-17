#!/bin/bash -eu
# SPDX-License-Identifier: Apache-2.0
# Builds sangam-packs' test classes and writes one launcher per Jazzer @FuzzTest method of PackParserFuzzTest into
# $OUT. The launchers run on the JDK 25 copied into $OUT (the runner image's JDK is older than Sangam's class files).
export JAVA_HOME=/opt/jdk25
export PATH="$JAVA_HOME/bin:$PATH"
cd "$SRC/sangam-repo/sangam"
bash ./mvnw -B -ntp -q -pl sangam-packs -am -DskipTests -Dcheckstyle.skip -Dspotbugs.skip install
bash ./mvnw -B -ntp -q -pl sangam-packs -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile="$WORK/cp.txt"

mkdir -p "$OUT/lib"
cp -r sangam-packs/target/test-classes "$OUT/lib/test-classes"
cp -r sangam-packs/target/classes "$OUT/lib/classes"
tr ':' '\n' < "$WORK/cp.txt" | while read -r jar; do [ -f "$jar" ] && cp "$jar" "$OUT/lib/"; done
cp -r /opt/jdk25 "$OUT/jdk"

RUNTIME_CP='$this_dir/lib/test-classes:$this_dir/lib/classes'
for jar in "$OUT"/lib/*.jar; do RUNTIME_CP="$RUNTIME_CP:\$this_dir/lib/$(basename "$jar")"; done

for method in packManifest bpmnModel dmnModel acceptanceSteps; do
  name="PackParserFuzzTest_$method"
  cat > "$OUT/$name" <<LAUNCHER
#!/bin/bash
# LLVMFuzzerTestOneInput (marker for the ClusterFuzzLite target scan)
this_dir=\$(dirname "\$0")
export JAVA_HOME="\$this_dir/jdk"
export LD_LIBRARY_PATH="\$this_dir/jdk/lib/server:\$this_dir"
if [[ "\$@" =~ (^| )-runs=[0-9]+(\$| ) ]]; then mem='-Xmx1900m:-Xss900k'; else mem='-Xmx2048m:-Xss1024k'; fi
exec "\$this_dir/jazzer_driver" --agent_path="\$this_dir/jazzer_agent_deploy.jar" \
  --cp="$RUNTIME_CP" \
  --target_class=org.sangam.packs.PackParserFuzzTest --target_method=$method \
  --jvm_args="\$mem" "\$@"
LAUNCHER
  chmod +x "$OUT/$name"
done
