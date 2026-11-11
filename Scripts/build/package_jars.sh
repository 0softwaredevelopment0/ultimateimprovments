#!/usr/bin/env bash
# Packages the current UltimateImprovments module JARs into one distribution archive.
#
# Pipeline:
#   1. ./gradlew distributeJars (skipped with --no-build)
#   2. tar only UI-*-<version>-all.jar -> build/distribution/UltimateImprovments-<version>-jars.tar
#
# JARs are already compressed ZIP files, so the archive is intentionally not gzipped.
#
# Usage:
#   bash Scripts/build/package_jars.sh [--no-build]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ "${1:-}" == "--no-build" ]]; then
  echo "[package] Skipping build (--no-build)"
else
  echo "[package] Building shadow JARs..."
  ./gradlew distributeJars -q
fi

CORE_JAR="$(find build/libs -maxdepth 1 -type f -name 'UI-Core-*-all.jar' -print -quit)"
if [[ -z "$CORE_JAR" ]]; then
  echo "[package] ERROR: UI-Core-*-all.jar not found in build/libs/" >&2
  exit 1
fi

# Extract the version from the JAR FILE NAME, never from its binary contents.
CORE_NAME="${CORE_JAR##*/}"
VERSION="${CORE_NAME#UI-Core-}"
VERSION="${VERSION%-all.jar}"
if [[ -z "$VERSION" || "$VERSION" == "$CORE_NAME" ]]; then
  echo "[package] ERROR: could not determine version from $CORE_NAME" >&2
  exit 1
fi

mapfile -t JARS < <(
  find build/libs -maxdepth 1 -type f -name "UI-*-${VERSION}-all.jar" -printf '%f\n' | sort
)
if (( ${#JARS[@]} == 0 )); then
  echo "[package] ERROR: no UI JARs found for version $VERSION" >&2
  exit 1
fi

STAGE="build/distribution"
mkdir -p "$STAGE"
TAR="$STAGE/UltimateImprovments-${VERSION}-jars.tar"

echo "[package] Packaging ${#JARS[@]} JAR(s) for version $VERSION..."
tar -cf "$TAR" -C build/libs "${JARS[@]}"

echo "[package] Done:"
ls -lh "$TAR"
sha256sum "$TAR"
echo "[package] Archive contents:"
tar -tf "$TAR"
