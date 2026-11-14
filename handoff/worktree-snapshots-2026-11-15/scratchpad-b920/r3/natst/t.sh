set -u
A=/w/nats-addon.sh
mkdir -p /tmp/bin; printf '#!/bin/sh\ncat "$LISTING"\n' > /tmp/bin/jar; chmod +x /tmp/bin/jar; export PATH=/tmp/bin:$PATH
touch /tmp/fat.jar
printf 'BOOT-INF/lib/spring-core-7.0.0.jar\n' > /tmp/clean.txt
printf 'BOOT-INF/lib/spring-core-7.0.0.jar\nBOOT-INF/lib/jnats-2.26.4.jar\n' > /tmp/dirty.txt
printf 'BOOT-INF/lib/bcprov-lts8on-2.73.12.1.jar\n' > /tmp/dirtybc.txt
mk() { rm -rf /tmp/d /tmp/o; mkdir -p /tmp/d; for j in "$@"; do touch "/tmp/d/$j"; done; }
run() { LISTING=$1 sh $A /tmp/fat.jar /tmp/d /tmp/o >/dev/null 2>&1; echo "$2 -> exit $?"; }
sh -n $A; echo "sh -n exit $?"
mk jnats-2.26.4.jar bcprov-lts8on-2.73.12.1.jar; run /tmp/clean.txt "pair, clean server"; ls /tmp/o | tr '\n' ' '; echo
mk jnats-2.26.4.jar bcprov-lts8on-2.73.12.1.jar; run /tmp/dirty.txt "server still ships jnats"
mk jnats-2.26.4.jar bcprov-lts8on-2.73.12.1.jar; run /tmp/dirtybc.txt "server ships lts8on"
mk jnats-2.26.4.jar; run /tmp/clean.txt "bcprov missing"
mk jnats-2.26.4.jar bcprov-lts8on-2.73.12.1.jar jspecify-1.0.1.jar; run /tmp/clean.txt "extra jar"
mk jnats-2.26.4.jar bcprov-lts8on-2.73.12.1.jar; mkdir -p /tmp/o; touch /tmp/o/bcprov-jdk18on-1.84.jar; LISTING=/tmp/clean.txt sh $A /tmp/fat.jar /tmp/d /tmp/o >/dev/null 2>&1; echo "esign already in out -> exit $?"