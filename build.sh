#!/usr/bin/env bash
# Build Fleet Commander: compile src/ against the game's jars into jars/FleetCommander.jar,
# then validate every doctrine and config file offline.
#
#   ./build.sh                 build + validate
#   ./build.sh --no-validate   build only
#
# Finds the game's jars in $STARSECTOR_CORE, or relative to the mod folder:
# <game>/mods/<this mod> -> <game>/starsector-core (Windows) or <game> (Linux).
# Needs a JDK 17+ (javac, jar). The game bundles a JRE only.
set -euo pipefail
cd "$(dirname "$0")"

find_core() {
    for d in "${STARSECTOR_CORE:-}" ../../starsector-core ../.. ../../Contents/Resources/Java; do
        if [ -n "$d" ] && [ -f "$d/starfarer.api.jar" ]; then echo "$d"; return 0; fi
    done
    return 1
}
CORE="$(find_core)" || { echo "error: starfarer.api.jar not found. Set STARSECTOR_CORE to the folder that holds it." >&2; exit 1; }

SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
LOG4J="$(ls "$CORE"/log4j-*.jar | head -n 1)"
CP="$CORE/starfarer.api.jar${SEP}$CORE/json.jar${SEP}$LOG4J${SEP}$CORE/lwjgl.jar${SEP}$CORE/lwjgl_util.jar"

rm -rf build/classes build/tools && mkdir -p build/classes build/tools jars
find src -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:unchecked -cp "$CP" -d build/classes @build/sources.txt
jar cf jars/FleetCommander.jar -C build/classes .
echo "built jars/FleetCommander.jar ($(find build/classes -name '*.class' | wc -l | tr -d ' ') classes)"

if [ "${1:-}" != "--no-validate" ]; then
    javac --release 17 -encoding UTF-8 -cp "build/classes${SEP}$CP" -d build/tools tools/ValidateConfig.java
    java -cp "build/tools${SEP}build/classes${SEP}$CP" ValidateConfig .
fi
