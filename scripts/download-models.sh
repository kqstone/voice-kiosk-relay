#!/usr/bin/env bash
set -eo pipefail

MODEL_NAME="sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
ARCHIVE_NAME="${MODEL_NAME}.tar.bz2"
EXPECTED_SHA256="b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${ROOT_DIR}/android/app/src/main/assets/models/${MODEL_NAME}"
CACHE_DIR="${ROOT_DIR}/models"

# Helper for retrying downloads with exponential backoff
download_with_retry() {
    local url="$1"
    local dest="$2"
    local max_attempts="${3:-3}"
    local attempt=1
    local delay=2

    while [ "${attempt}" -le "${max_attempts}" ]; do
        echo "  [Attempt ${attempt}/${max_attempts}] Downloading ${url}..."
        rm -f "${dest}"
        local http_code
        local curl_exit=0
        http_code=$(curl -sSL --connect-timeout 20 -w "%{http_code}" -o "${dest}" "${url}") || curl_exit=$?
        if [ "${curl_exit}" -eq 0 ] && [ "${http_code}" = "200" ] && [ -s "${dest}" ]; then
            return 0
        fi
        echo "  Attempt ${attempt} failed (HTTP status: ${http_code}, curl exit: ${curl_exit})." >&2
        rm -f "${dest}"
        if [ "${attempt}" -lt "${max_attempts}" ]; then
            echo "  Retrying in ${delay}s..." >&2
            sleep "${delay}"
            delay=$((delay * 2))
        fi
        attempt=$((attempt + 1))
    done
    return 1
}

slim_models() {
    echo "Slimming model assets in ${TARGET_DIR}..."
    rm -rf "${TARGET_DIR}/test_wavs"
    find "${TARGET_DIR}" -mindepth 1 \
        -not -name "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
        -not -name "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
        -not -name "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
        -not -name "tokens.txt" \
        -not -name "keywords.txt" \
        -delete 2>/dev/null || true
    # Assets are gitignored, so CI must reproduce the default wake-word file.
    printf '%s\n' 'n ǐ h ǎo x iǎo zh ì #0.25 @你好小智' > "${TARGET_DIR}/keywords.txt"
}

ensure_aar() {
    LIBS_DIR="${ROOT_DIR}/android/app/libs"
    AAR_NAME="sherpa-onnx-2.0.1.aar"
    AAR_PATH="${LIBS_DIR}/${AAR_NAME}"
    AAR_SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

    if [ -f "${AAR_PATH}" ]; then
        local existing_sha
        existing_sha=$(sha256sum "${AAR_PATH}" | awk '{print $1}')
        if [ "${existing_sha}" = "${AAR_SHA256}" ]; then
            echo "Runtime AAR ready at ${AAR_PATH}."
            return 0
        fi
        echo "Existing AAR SHA256 mismatch (expected ${AAR_SHA256}, got ${existing_sha}), re-downloading..." >&2
        rm -f "${AAR_PATH}"
    fi

    echo "Downloading Sherpa-ONNX runtime AAR to ${AAR_PATH}..."
    mkdir -p "${LIBS_DIR}"

    local AAR_SOURCES=(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/${AAR_NAME}"
        "https://downloads.sourceforge.net/project/sherpa-onnx.mirror/v2.0.1/${AAR_NAME}"
    )

    local aar_downloaded=false
    for aar_url in "${AAR_SOURCES[@]}"; do
        echo "Attempting AAR download from ${aar_url}..."
        if download_with_retry "${aar_url}" "${AAR_PATH}.tmp" 3; then
            local actual_aar_sha
            actual_aar_sha=$(sha256sum "${AAR_PATH}.tmp" | awk '{print $1}')
            if [ "${actual_aar_sha}" = "${AAR_SHA256}" ]; then
                mv "${AAR_PATH}.tmp" "${AAR_PATH}"
                echo "Runtime AAR verified successfully from ${aar_url}."
                aar_downloaded=true
                break
            else
                echo "SHA256 mismatch for AAR downloaded from ${aar_url}: expected ${AAR_SHA256}, got ${actual_aar_sha}" >&2
                rm -f "${AAR_PATH}.tmp"
            fi
        else
            echo "AAR download failed from ${aar_url} after retries." >&2
        fi
    done

    if [ "${aar_downloaded}" != "true" ]; then
        echo "Failed to obtain valid runtime AAR from all sources." >&2
        exit 1
    fi

    echo "Runtime AAR ready at ${AAR_PATH}."
}

# Check if model already exists and contains expected files
if [ -d "${TARGET_DIR}" ] && [ -f "${TARGET_DIR}/tokens.txt" ]; then
    slim_models
    ensure_aar
    echo "Model already downloaded at ${TARGET_DIR}."
    exit 0
fi

mkdir -p "${CACHE_DIR}"
mkdir -p "${TARGET_DIR}"

ARCHIVE_PATH="${CACHE_DIR}/${ARCHIVE_NAME}"

# Archive sources:
# Note: HuggingFace repo csukuangfj/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01
# returns HTTP 401 Unauthorized (requires login), so it was removed as a primary source.
# GitHub Releases is used for archive downloads.
declare -a SOURCES=(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${ARCHIVE_NAME}"
)

download_success=false

for URL in "${SOURCES[@]}"; do
    echo "Attempting to download ${MODEL_NAME} archive from ${URL}..."
    if download_with_retry "${URL}" "${ARCHIVE_PATH}.tmp" 3; then
        mv "${ARCHIVE_PATH}.tmp" "${ARCHIVE_PATH}"
        echo "Download succeeded from ${URL}."
        download_success=true
        break
    else
        echo "Download failed from source URL: ${URL} after retries." >&2
        rm -f "${ARCHIVE_PATH}.tmp"
    fi
done

# If archive download failed, try ModelScope individual files fallback
if [ "${download_success}" != "true" ]; then
    echo "Archive sources failed. Attempting ModelScope file-by-file fallback..."
    MODELSCOPE_BASE="https://www.modelscope.cn/api/v1/models/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01/repo?Revision=master&FilePath="
    FILES=(
        "tokens.txt:72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e"
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx:017af32f2c0138f931d05fbc009ee864295e910aff304f77d2f563815fc834fb"
        "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx:fe53b8d6a07bc5373d1770649025a5a985c8fb3dab70323386a1b73aacba0546"
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx:431de10b554f134ef8af320feea2db337e641290449a3d3f6cb6e5f5fd2c9c3d"
    )

    ms_all_ok=true
    for item in "${FILES[@]}"; do
        fname="${item%%:*}"
        fsha="${item##*:}"
        file_url="${MODELSCOPE_BASE}${fname}"
        out_file="${TARGET_DIR}/${fname}"
        echo "Downloading ${fname} from ModelScope: ${file_url}..."
        if ! download_with_retry "${file_url}" "${out_file}.tmp" 3; then
            echo "ModelScope download failed for ${fname} from ${file_url} after retries" >&2
            ms_all_ok=false
            break
        fi
        actual_sha=$(sha256sum "${out_file}.tmp" | awk '{print $1}')
        if [ "${actual_sha}" != "${fsha}" ]; then
            echo "SHA256 mismatch for ${fname} from ${file_url}: expected ${fsha}, got ${actual_sha}" >&2
            rm -f "${out_file}.tmp"
            ms_all_ok=false
            break
        fi
        mv "${out_file}.tmp" "${out_file}"
        echo "Verified SHA256 for ${fname}."
    done

    if [ "${ms_all_ok}" = "true" ]; then
        echo "Successfully downloaded and verified all files from ModelScope."
        slim_models
        ensure_aar
        exit 0
    else
        echo "All download sources exhausted. Failed to obtain model." >&2
        exit 1
    fi
fi

# Verify archive SHA256
ACTUAL_SHA256=$(sha256sum "${ARCHIVE_PATH}" | awk '{print $1}')
if [ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]; then
    echo "SHA256 verification failed for ${ARCHIVE_PATH}!" >&2
    echo "Expected: ${EXPECTED_SHA256}" >&2
    echo "Actual:   ${ACTUAL_SHA256}" >&2
    exit 1
fi

echo "SHA256 verified successfully: ${ACTUAL_SHA256}"

# Extract archive
echo "Extracting model archive to ${TARGET_DIR}..."
tar --no-same-owner -xjf "${ARCHIVE_PATH}" -C "${CACHE_DIR}"
if [ -d "${CACHE_DIR}/${MODEL_NAME}" ]; then
    cp -r "${CACHE_DIR}/${MODEL_NAME}/"* "${TARGET_DIR}/"
fi

# Slim the model assets and ensure AAR
slim_models
ensure_aar

echo "Model ready at ${TARGET_DIR}."
