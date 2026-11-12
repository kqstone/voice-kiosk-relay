#!/usr/bin/env bash
# Fetch the pinned llama.cpp source tree used by the Android on-device LLM (S0).
#
# The source is NOT committed (keeps the repo light). It is fetched into
# android/app/src/main/cpp/third_party/llama.cpp (gitignored) and verified:
#   1. primary: GitHub release tarball, verified by SHA256;
#   2. fallback: shallow git fetch of the pinned commit, verified by commit SHA.
# Bumping the pin: update all three values below together and re-run the
# Android build + unit tests (see docs/MODELS.md "llama.cpp pin").
set -euo pipefail

LLAMA_CPP_TAG="v0.9.0"
LLAMA_CPP_COMMIT="cd959bae99ff4bed22bef2604f45241c09bbb8c8"
LLAMA_CPP_TARBALL_SHA256="5d0f1aaf55fd8629c29a55e81b7cb63b80239f83c33a580963071204896836a8"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_PARENT="${ROOT_DIR}/android/app/src/main/cpp/third_party"
DEST="${DEST_PARENT}/llama.cpp"
STAMP="${DEST}/.voice-kiosk-pin"
CACHE_DIR="${ROOT_DIR}/models"
TARBALL="${CACHE_DIR}/llama.cpp-${LLAMA_CPP_TAG}.tar.gz"
EXPECTED_STAMP="${LLAMA_CPP_TAG} ${LLAMA_CPP_COMMIT}"

if [ -f "${STAMP}" ] && [ "$(cat "${STAMP}")" = "${EXPECTED_STAMP}" ] && [ -f "${DEST}/CMakeLists.txt" ]; then
    echo "llama.cpp ${LLAMA_CPP_TAG} already present at ${DEST}."
    exit 0
fi

mkdir -p "${DEST_PARENT}" "${CACHE_DIR}"
rm -rf "${DEST}" "${DEST}.tmp"

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

fetch_tarball() {
    local url="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz"
    if [ ! -f "${TARBALL}" ] || [ "$(sha256_of "${TARBALL}")" != "${LLAMA_CPP_TARBALL_SHA256}" ]; then
        rm -f "${TARBALL}" "${TARBALL}.part"
        echo "Downloading ${url} ..."
        curl -fsSL --retry 3 --connect-timeout 20 -o "${TARBALL}.part" "${url}" || return 1
        mv "${TARBALL}.part" "${TARBALL}"
    fi
    local actual
    actual="$(sha256_of "${TARBALL}")"
    if [ "${actual}" != "${LLAMA_CPP_TARBALL_SHA256}" ]; then
        echo "llama.cpp tarball SHA256 mismatch: expected ${LLAMA_CPP_TARBALL_SHA256}, got ${actual}" >&2
        rm -f "${TARBALL}"
        return 1
    fi
    mkdir -p "${DEST}.tmp"
    tar --no-same-owner -xzf "${TARBALL}" -C "${DEST}.tmp" --strip-components=1
    mv "${DEST}.tmp" "${DEST}"
}

fetch_git() {
    echo "Falling back to git fetch of pinned commit ${LLAMA_CPP_COMMIT} ..."
    rm -rf "${DEST}.tmp"
    git init -q "${DEST}.tmp"
    git -C "${DEST}.tmp" fetch -q --depth 1 https://github.com/ggml-org/llama.cpp.git "${LLAMA_CPP_COMMIT}"
    git -C "${DEST}.tmp" checkout -q FETCH_HEAD
    local head
    head="$(git -C "${DEST}.tmp" rev-parse HEAD)"
    if [ "${head}" != "${LLAMA_CPP_COMMIT}" ]; then
        echo "llama.cpp commit mismatch: expected ${LLAMA_CPP_COMMIT}, got ${head}" >&2
        rm -rf "${DEST}.tmp"
        return 1
    fi
    rm -rf "${DEST}.tmp/.git"
    mv "${DEST}.tmp" "${DEST}"
}

if ! fetch_tarball; then
    rm -rf "${DEST}.tmp"
    fetch_git
fi

printf '%s' "${EXPECTED_STAMP}" > "${STAMP}"
echo "llama.cpp ${LLAMA_CPP_TAG} (${LLAMA_CPP_COMMIT}) ready at ${DEST}."
