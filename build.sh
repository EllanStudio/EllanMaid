#!/usr/bin/env bash
# Build EllanMaid without Gradle. Usage:
#   SERVER=/path/to/paper-server ./build.sh
# SERVER must contain libraries/ (paper/leaf api, adventure, gson, joml) and
# plugins/ with ModelEngine-*.jar and craft-engine-paper-plugin-*.jar.
set -euo pipefail
cd "$(dirname "$0")"
: "${SERVER:?set SERVER to a Paper/Leaf server directory}"
JAVAC=${JAVAC:-javac}
VER=$(sed -n 's/^version: *//p' plugin.yml)
CP=$(find "$SERVER/libraries" -name '*.jar' | tr '\n' :)
CP+=$(ls "$SERVER"/plugins/ModelEngine-*.jar | head -1):$(ls "$SERVER"/plugins/craft-engine-paper-plugin-*.jar | head -1)
rm -rf out && mkdir out
"$JAVAC" -nowarn --release 25 -d out -cp "$CP" $(find src vendor -name '*.java')
cp plugin.yml config.yml out/
(cd out && jar cf "../EllanMaid-$VER.jar" .)
echo "built EllanMaid-$VER.jar"
