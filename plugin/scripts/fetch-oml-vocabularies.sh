#!/usr/bin/env bash
# fetch-oml-vocabularies.sh — produce the openCAESAR IMCE systems-engineering vocabularies as
# OWL and stage them into build/catalog-bundle for deployPlugin to index. Apache-2.0.
#
# Owner policy: we never commit the ontology files; this SCRIPT (committed) regenerates them on
# demand from the canonical openCAESAR source using their oml2owl toolchain (JDK 21+).
#
# Usage (from the plugin/ directory):   bash scripts/fetch-oml-vocabularies.sh
# Then:                                 gradle deployPlugin
#
# The IMCE foundation vocabularies (base/mission/analysis/project) provide MBSE upper concepts
# (Component, Function, Mission, Interface, Flow, …) that align with UAF elements - filling the
# systems-engineering gap the life-science OLS4 leaves.
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${PLUGIN_DIR}/build/oml-work"
DEST="${PLUGIN_DIR}/build/catalog-bundle"
REPO="https://github.com/opencaesar/imce-vocabularies.git"

mkdir -p "${DEST}"
if [ ! -d "${WORK}/imce/.git" ]; then
  echo "[oml] cloning ${REPO}"
  rm -rf "${WORK}/imce"
  git clone --depth 1 "${REPO}" "${WORK}/imce"
fi

echo "[oml] running omlToOwl (downloads OML deps + converts to OWL)"
( cd "${WORK}/imce" && ./gradlew omlToOwl --console=plain )

SRC="${WORK}/imce/build/owl/imce.jpl.nasa.gov/foundation"
for f in base mission analysis project; do
  if [ -f "${SRC}/${f}.owl" ]; then
    cp "${SRC}/${f}.owl" "${DEST}/imce-${f}.owl"
    echo "[oml] staged imce-${f}.owl ($(wc -c < "${DEST}/imce-${f}.owl") bytes)"
  else
    echo "[oml] WARN: ${SRC}/${f}.owl not produced - skipping" >&2
  fi
done
echo "[oml] done. Now run: gradle deployPlugin"
