#!/usr/bin/env bash
# Mac: compiles the seeding / journal fabricator (server/src/test/java/crowdvsmarket/server/bench/) and packs only its classes into
# server/target/bench-fabricator.jar (design §4.3, step 3). The jar has no dependencies of its own: it runs inside the server image on
# the image's classpath (/deployments/lib/main/*, /deployments/app/*), so it sees exactly the server classes of that image.
# The server image and this jar must come from the SAME tree: ship.sh --worktree builds both from the working tree.
#   tools/bench/build-fabricator.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JDK21=${JDK21:-$HOME/.sdkman/candidates/java/21.0.11-oracle}
[ -d "$JDK21" ] && export JAVA_HOME=$JDK21
SETTINGS=()
[ -f "$HOME/.m2/settings-oss.xml" ] && SETTINGS=(-s "$HOME/.m2/settings-oss.xml")
(cd "$ROOT" && mvn -B -q "${SETTINGS[@]}" -pl server -am test-compile -DskipTests)
CLASSES="$ROOT/server/target/test-classes"
OUT="$ROOT/server/target/bench-fabricator.jar"
args=()
while IFS= read -r f; do args+=(-C "$CLASSES" "$f"); done < <(cd "$CLASSES" && find crowdvsmarket/server/bench -name 'Bench*.class' | sort)
[ "${#args[@]}" -gt 0 ] || { echo "no Bench*.class in $CLASSES" >&2; exit 1; }
rm -f "$OUT"
"$JAVA_HOME/bin/jar" cf "$OUT" "${args[@]}"
echo "$OUT"
