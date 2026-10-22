#!/bin/bash
# Power-loss test of the Chronicle journal on LazyFS: positive x3 (synced child: every ACKed batch must survive) and the negative
# control (child without sync: acknowledged batches MUST go missing, else the harness does not see mmap writes). Needs docker with /dev/fuse.
set -u
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-oracle; export PATH=$JAVA_HOME/bin:$PATH
ACKS=${ACKS:-200}; RUNS=${RUNS:-3}
docker build --network host -q -t journal-powerloss "$HERE" >/dev/null || { echo "docker build failed"; exit 2; }
(cd "$ROOT/server" && mvn -s ~/.m2/settings-oss.xml -o -q test-compile \
  && mvn -s ~/.m2/settings-oss.xml -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$HERE/.cp.txt") || exit 2
# the classpath of the host, with the host paths mapped to the read-only mounts
CP=$(sed -e "s#$HOME/.m2/repository-oss#/m2#g" -e "s#$ROOT/server/target#/target#g" "$HERE/.cp.txt"):/target/classes:/target/test-classes
FLAGS="--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"
one() { # mode
  docker run --rm --device /dev/fuse --cap-add SYS_ADMIN --security-opt apparmor:unconfined \
    -v "$HOME/.m2/repository-oss:/m2:ro" -v "$ROOT/server/target:/target:ro" -e FUSEOPTS="${FUSEOPTS:-}" -e CP="$CP" -e JAVA_FLAGS="$FLAGS" \
    journal-powerloss "$1" "$ACKS"
}
pass=0; for i in $(seq "$RUNS"); do
  echo "=== positive run $i (journal with sync) ==="; one sync | tee "$HERE/.pos$i.log" | tail -8
  grep -q '^RESULT PASS' "$HERE/.pos$i.log" && pass=$((pass+1))
done
echo "=== negative control (journal WITHOUT sync) ==="; one nosync | tee "$HERE/.neg.log" | tail -8
if grep -q "^child died early" "$HERE/.neg.log" "$HERE/.pos1.log"; then echo "--- VERDICT: BLOCKED - the child died before it acknowledged anything (SIGBUS on the mmap of a file on the LazyFS mount, see .pos1.log); nothing is proven either way"; exit 3; fi
neg=0; grep -q '^RESULT FAIL.*\(LOST\|OPEN/READ FAILED\)' "$HERE/.neg.log" && neg=1
echo "--- VERDICT: positive $pass/$RUNS passed; negative control $([ $neg = 1 ] && echo 'lost acknowledged data (method sees the power cut)' || echo 'LOST NOTHING (method is blind, positive result proves nothing)')"
[ "$pass" = "$RUNS" ] && [ $neg = 1 ]
