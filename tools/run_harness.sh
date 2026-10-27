#!/usr/bin/env bash
# Offline smoke test of the commander loop with faked game interfaces (tools/harness). Run after ./build.sh.
# Needs the same STARSECTOR_CORE as build.sh. Not part of the mod.
set -euo pipefail
cd "$(dirname "$0")/.."
CORE="${STARSECTOR_CORE:-../../starsector-core}"
LOG4J="$(ls "$CORE"/log4j-*.jar | head -n 1)"
CP="build/classes:$CORE/starfarer.api.jar:$CORE/json.jar:$LOG4J:$CORE/lwjgl.jar:$CORE/lwjgl_util.jar"
rm -rf build/harness && mkdir -p build/harness
javac --release 17 -encoding UTF-8 -cp "$CP" -d build/harness $(find tools/harness -name '*.java')
java -cp "build/harness:$CP" fleetcommander.combat.SimHarness .
