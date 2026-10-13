#!/usr/bin/env sh
# Compiles the chess bot into ./build (requires JDK 17+).
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes build/test-classes
javac -d build/classes src/chess/*.java
javac -cp build/classes -d build/test-classes test/chess/*.java
jar --create --file build/chessbot.jar --main-class chess.Main -C build/classes .
echo "Built build/chessbot.jar"
