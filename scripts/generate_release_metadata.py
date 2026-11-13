#!/usr/bin/env python3
"""
Generate release-metadata.json for voice-kiosk-relay releases.

Defines and produces the metadata schema required by ReleaseUpdateChecker.kt:
- schemaVersion: 1
- repository: kqstone/voice-kiosk-relay
- applicationId: com.voicekiosk.app
- versionCode: positive integer (> 0)
- versionName: semantic version string (e.g. 0.1.7)
- tagName: tag string matching v<versionName> (e.g. v0.1.7)
- minSdk: integer (28)
- targetSdk: integer (31)
- signingCertSha256: 64 lowercase hex characters of the APK signing cert DER
- apks: array of 1 object describing canonical APK asset:
    abi: arm64-v8a
    name: voice-kiosk-<tagName>-arm64-v8a.apk
    size: positive integer (> 0)
    sha256: 64 lowercase hex characters
"""

import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from typing import Any, Dict, Optional, Tuple


EXPECTED_SCHEMA_VERSION = 1
EXPECTED_REPOSITORY = "kqstone/voice-kiosk-relay"
EXPECTED_APPLICATION_ID = "com.voicekiosk.app"
EXPECTED_ABI = "arm64-v8a"
METADATA_ASSET_NAME = "release-metadata.json"

TAG_REGEX = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$")
VERSION_NAME_REGEX = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$")
SHA256_HEX_REGEX = re.compile(r"^[0-9a-f]{64}$")
SAFE_ASSET_NAME_REGEX = re.compile(r"^[A-Za-z0-9._-]+$")

APK_SIG_V2_ID = 0x7109871A
APK_SIG_V3_ID = 0xF05368C0
APK_SIG_V31_ID = 0x1B93AD61


def validate_tag_name(tag: str) -> str:
    tag = tag.strip()
    if not TAG_REGEX.match(tag) or ".." in tag:
        raise ValueError(f"Invalid tag name: '{tag}', must match v<major>.<minor>.<patch>")
    return tag


def validate_safe_asset_name(name: str) -> str:
    if (
        not name
        or name in (".", "..")
        or "/" in name
        or "\\" in name
        or ".." in name
        or not SAFE_ASSET_NAME_REGEX.match(name)
    ):
        raise ValueError(f"Unsafe or invalid asset name: '{name}'")
    return name


def canonical_apk_name(tag_name: str, abi: str = EXPECTED_ABI) -> str:
    validated_tag = validate_tag_name(tag_name)
    if abi != EXPECTED_ABI:
        raise ValueError(f"Unsupported ABI: '{abi}', expected '{EXPECTED_ABI}'")
    return validate_safe_asset_name(f"voice-kiosk-{validated_tag}-{abi}.apk")


def parse_apk_signing_block_der(apk_path: str) -> bytes:
    """
    Direct pure-Python parser for APK Signing Block (APK Signature Scheme v2/v3).
    Extracts the DER bytes of the primary signing certificate without external dependencies.
    """
    with open(apk_path, "rb") as f:
        f.seek(0, os.SEEK_END)
        file_size = f.tell()
        if file_size < 32:
            raise ValueError(f"File too small to be an APK: {file_size} bytes")

        # Locate ZIP End of Central Directory (EOCD)
        max_search = min(file_size, 65535 + 22)
        f.seek(file_size - max_search)
        tail = f.read(max_search)

        eocd_magic = b"PK\x05\x06"
        eocd_rel = tail.rfind(eocd_magic)
        if eocd_rel == -1:
            raise ValueError("ZIP End of Central Directory (EOCD) record not found")

        cd_offset = struct.unpack("<I", tail[eocd_rel + 16 : eocd_rel + 20])[0]
        if cd_offset < 24:
            raise ValueError(f"Invalid Central Directory offset: {cd_offset}")

        # Check for APK Signing Block magic immediately preceding the Central Directory
        f.seek(cd_offset - 24)
        footer = f.read(24)
        block_size = struct.unpack("<Q", footer[:8])[0]
        magic = footer[8:]
        if magic != b"APK Sig Block 42":
            raise ValueError("APK Signing Block magic 'APK Sig Block 42' not found")

        block_start = cd_offset - block_size - 8
        if block_start < 0:
            raise ValueError(f"Invalid APK Signing Block start offset: {block_start}")

        f.seek(block_start)
        first_size = struct.unpack("<Q", f.read(8))[0]
        if first_size != block_size:
            raise ValueError(f"APK Signing Block header size ({first_size}) != footer size ({block_size})")

        pairs_data = f.read(block_size - 16)

    # Parse ID-value pairs in the APK Signing Block
    pos = 0
    sig_blocks = []
    while pos + 12 <= len(pairs_data):
        pair_len = struct.unpack("<Q", pairs_data[pos : pos + 8])[0]
        pos += 8
        if pos + pair_len > len(pairs_data) or pair_len < 4:
            break
        pair_id = struct.unpack("<I", pairs_data[pos : pos + 4])[0]
        pair_val = pairs_data[pos + 4 : pos + pair_len]
        pos += pair_len
        if pair_id in (APK_SIG_V31_ID, APK_SIG_V3_ID, APK_SIG_V2_ID):
            sig_blocks.append((pair_id, pair_val))

    if not sig_blocks:
        raise ValueError("No APK Signature Scheme v2/v3 ID-value pair found in signing block")

    # Prefer v3.1, then v3, then v2
    def priority(pid: int) -> int:
        if pid == APK_SIG_V31_ID:
            return 0
        if pid == APK_SIG_V3_ID:
            return 1
        return 2

    sig_blocks.sort(key=lambda item: priority(item[0]))
    _, sig_data = sig_blocks[0]

    # In v2/v3 structure:
    # signers sequence (length-prefixed)
    if len(sig_data) < 4:
        raise ValueError("Malformed signature block data (too short for signers sequence)")
    signers_len = struct.unpack("<I", sig_data[:4])[0]
    signers_data = sig_data[4 : 4 + signers_len]

    # First signer (length-prefixed)
    if len(signers_data) < 4:
        raise ValueError("Malformed signers sequence (too short)")
    signer_len = struct.unpack("<I", signers_data[:4])[0]
    single_signer = signers_data[4 : 4 + signer_len]

    # Signed data (length-prefixed)
    if len(single_signer) < 4:
        raise ValueError("Malformed signer entry (too short)")
    signed_data_len = struct.unpack("<I", single_signer[:4])[0]
    signed_data = single_signer[4 : 4 + signed_data_len]

    # Inside signed_data:
    # digests sequence (length-prefixed)
    if len(signed_data) < 4:
        raise ValueError("Malformed signed data (too short)")
    digests_len = struct.unpack("<I", signed_data[:4])[0]
    certs_offset = 4 + digests_len

    # certificates sequence (length-prefixed)
    if len(signed_data) < certs_offset + 4:
        raise ValueError("Malformed signed data (missing certificates sequence)")
    certs_seq_len = struct.unpack("<I", signed_data[certs_offset : certs_offset + 4])[0]
    certs_data = signed_data[certs_offset + 4 : certs_offset + 4 + certs_seq_len]

    # Primary signer certificate (length-prefixed DER bytes)
    if len(certs_data) < 4:
        raise ValueError("Malformed certificates sequence (no certificate found)")
    cert_len = struct.unpack("<I", certs_data[:4])[0]
    cert_der = certs_data[4 : 4 + cert_len]
    if len(cert_der) != cert_len or cert_len == 0:
        raise ValueError(f"Invalid certificate DER length: {len(cert_der)} (expected {cert_len})")

    return cert_der


def extract_signing_cert_via_apksigner(apk_path: str) -> Optional[str]:
    """Fallback: try running apksigner verify --print-certs if apksigner is installed."""
    candidates = ["apksigner"]
    for env_var in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk_root = os.environ.get(env_var)
        if sdk_root and os.path.isdir(sdk_root):
            bt_dir = os.path.join(sdk_root, "build-tools")
            if os.path.isdir(bt_dir):
                for version in sorted(os.listdir(bt_dir), reverse=True):
                    candidate = os.path.join(bt_dir, version, "apksigner")
                    if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                        candidates.insert(0, candidate)

    for apksigner_cmd in candidates:
        try:
            res = subprocess.run(
                [apksigner_cmd, "verify", "--print-certs", apk_path],
                capture_output=True,
                text=True,
                check=False,
            )
            if res.returncode == 0:
                match = re.search(r"Signer\s+#1\s+certificate\s+SHA-256\s+digest:\s*([0-9a-fA-F]{64})", res.stdout)
                if match:
                    return match.group(1).lower()
        except Exception:
            continue
    return None


def extract_signing_cert_via_keytool(apk_path: str) -> Optional[str]:
    """Fallback: try running keytool -printcert -jarfile if keytool is installed."""
    try:
        res = subprocess.run(
            ["keytool", "-printcert", "-jarfile", apk_path],
            capture_output=True,
            text=True,
            check=False,
        )
        if res.returncode == 0:
            match = re.search(r"SHA256:\s*([0-9A-Fa-f: ]{64,})", res.stdout)
            if match:
                raw_hex = match.group(1).replace(":", "").replace(" ", "").strip().lower()
                if SHA256_HEX_REGEX.match(raw_hex):
                    return raw_hex
    except Exception:
        pass
    return None


def extract_signing_cert_via_v1_openssl(apk_path: str) -> Optional[str]:
    """Fallback: extract META-INF/*.RSA or *.DSA or *.EC and run openssl pkcs7."""
    try:
        with zipfile.ZipFile(apk_path, "r") as z:
            sig_files = [
                n
                for n in z.namelist()
                if n.startswith("META-INF/")
                and (n.endswith(".RSA") or n.endswith(".DSA") or n.endswith(".EC"))
            ]
            if not sig_files:
                return None
            rsa_bytes = z.read(sig_files[0])

        res = subprocess.run(
            ["openssl", "pkcs7", "-inform", "DER", "-print_certs", "-outform", "DER"],
            input=rsa_bytes,
            capture_output=True,
            check=False,
        )
        if res.returncode == 0 and res.stdout:
            # First certificate DER output
            cert_der = res.stdout
            return hashlib.sha256(cert_der).hexdigest().lower()
    except Exception:
        pass
    return None


def extract_signing_cert_sha256(apk_path: str) -> str:
    """
    Extract the 64-char lowercase hex SHA-256 fingerprint of the single active signing certificate
    from the given APK. Tries pure-Python APK Signing Block parser first, followed by standard tool fallbacks.
    """
    # 1. Pure-Python APK Signing Block parser (v2/v3)
    python_err = None
    try:
        cert_der = parse_apk_signing_block_der(apk_path)
        sha = hashlib.sha256(cert_der).hexdigest().lower()
        if SHA256_HEX_REGEX.match(sha):
            return sha
    except Exception as exc:
        python_err = exc

    # 2. apksigner fallback
    apksigner_sha = extract_signing_cert_via_apksigner(apk_path)
    if apksigner_sha:
        return apksigner_sha

    # 3. keytool fallback
    keytool_sha = extract_signing_cert_via_keytool(apk_path)
    if keytool_sha:
        return keytool_sha

    # 4. OpenSSL on v1 signature fallback
    openssl_sha = extract_signing_cert_via_v1_openssl(apk_path)
    if openssl_sha:
        return openssl_sha

    raise RuntimeError(
        f"Failed to extract APK signing certificate fingerprint from '{apk_path}'. "
        f"Pure-Python APK block error: {python_err}"
    )


def compute_file_sha256(file_path: str) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(64 * 1024):
            h.update(chunk)
    return h.hexdigest().lower()


def parse_build_gradle_config(gradle_file: Optional[str] = None) -> Dict[str, Any]:
    """Parse applicationId, minSdk, targetSdk from android/app/build.gradle.kts if available."""
    default_cfg = {
        "applicationId": EXPECTED_APPLICATION_ID,
        "minSdk": 28,
        "targetSdk": 31,
    }
    if not gradle_file:
        gradle_file = "android/app/build.gradle.kts"
    if not os.path.isfile(gradle_file):
        return default_cfg

    try:
        with open(gradle_file, "r", encoding="utf-8") as f:
            content = f.read()

        app_id_match = re.search(r'applicationId\s*=\s*"([^"]+)"', content)
        min_sdk_match = re.search(r"minSdk\s*=\s*(\d+)", content)
        target_sdk_match = re.search(r"targetSdk\s*=\s*(\d+)", content)

        if app_id_match:
            default_cfg["applicationId"] = app_id_match.group(1).strip()
        if min_sdk_match:
            default_cfg["minSdk"] = int(min_sdk_match.group(1))
        if target_sdk_match:
            default_cfg["targetSdk"] = int(target_sdk_match.group(1))
    except Exception:
        pass

    return default_cfg


def generate_metadata_dict(
    tag_name: str,
    version_name: str,
    version_code: int,
    apk_path: str,
    repository: str = EXPECTED_REPOSITORY,
    application_id: str = EXPECTED_APPLICATION_ID,
    min_sdk: int = 28,
    target_sdk: int = 31,
    signing_cert_sha256: Optional[str] = None,
    abi: str = EXPECTED_ABI,
) -> Dict[str, Any]:
    """Construct and validate metadata dictionary matching ReleaseUpdateChecker.kt."""
    tag_name = validate_tag_name(tag_name)
    version_name = version_name.strip().lstrip("v")
    if not VERSION_NAME_REGEX.match(version_name):
        raise ValueError(f"Invalid versionName: '{version_name}', must be semantic version X.Y.Z")

    if tag_name != f"v{version_name}":
        raise ValueError(f"tagName ('{tag_name}') does not match 'v' + versionName ('v{version_name}')")

    if not isinstance(version_code, int) or version_code <= 0:
        raise ValueError(f"versionCode must be a positive integer, got: {version_code}")

    if repository != EXPECTED_REPOSITORY:
        raise ValueError(f"repository must be '{EXPECTED_REPOSITORY}', got: '{repository}'")

    if application_id != EXPECTED_APPLICATION_ID:
        raise ValueError(f"applicationId must be '{EXPECTED_APPLICATION_ID}', got: '{application_id}'")

    if not isinstance(min_sdk, int) or min_sdk <= 0:
        raise ValueError(f"minSdk must be a positive integer, got: {min_sdk}")

    if not isinstance(target_sdk, int) or target_sdk < min_sdk:
        raise ValueError(f"targetSdk must be >= minSdk ({min_sdk}), got: {target_sdk}")

    if not os.path.isfile(apk_path):
        raise FileNotFoundError(f"APK file not found: {apk_path}")

    apk_size = os.path.getsize(apk_path)
    if apk_size <= 0:
        raise ValueError(f"APK file '{apk_path}' is empty (size={apk_size})")

    apk_sha256 = compute_file_sha256(apk_path)
    if not SHA256_HEX_REGEX.match(apk_sha256):
        raise ValueError(f"Malformed APK SHA-256 digest: '{apk_sha256}'")

    if not signing_cert_sha256:
        signing_cert_sha256 = extract_signing_cert_sha256(apk_path)
    else:
        signing_cert_sha256 = signing_cert_sha256.strip().lower()

    if not SHA256_HEX_REGEX.match(signing_cert_sha256):
        raise ValueError(
            f"signingCertSha256 must be 64 lowercase hex characters, got: '{signing_cert_sha256}'"
        )

    expected_apk_filename = canonical_apk_name(tag_name, abi)

    meta = {
        "schemaVersion": EXPECTED_SCHEMA_VERSION,
        "repository": repository,
        "applicationId": application_id,
        "versionCode": version_code,
        "versionName": version_name,
        "tagName": tag_name,
        "minSdk": min_sdk,
        "targetSdk": target_sdk,
        "signingCertSha256": signing_cert_sha256,
        "apks": [
            {
                "abi": abi,
                "name": expected_apk_filename,
                "size": apk_size,
                "sha256": apk_sha256,
            }
        ],
    }

    validate_metadata(meta)
    return meta


def validate_metadata(meta: Dict[str, Any]) -> None:
    """Rigorous mirror of all checks performed in ReleaseUpdateChecker.kt parseAndCrossValidateMetadata."""
    required_fields = [
        "schemaVersion",
        "repository",
        "applicationId",
        "versionCode",
        "versionName",
        "tagName",
        "minSdk",
        "targetSdk",
        "signingCertSha256",
        "apks",
    ]
    for field in required_fields:
        if field not in meta or meta[field] is None:
            raise ValueError(f"Missing required metadata field: {field}")

    if meta["schemaVersion"] != EXPECTED_SCHEMA_VERSION:
        raise ValueError(f"Unsupported schemaVersion: {meta['schemaVersion']}")

    if meta["repository"] != EXPECTED_REPOSITORY:
        raise ValueError(f"Repository mismatch: expected {EXPECTED_REPOSITORY}, got {meta['repository']}")

    if meta["applicationId"] != EXPECTED_APPLICATION_ID:
        raise ValueError(f"ApplicationId mismatch: expected {EXPECTED_APPLICATION_ID}, got {meta['applicationId']}")

    version_code = meta["versionCode"]
    if not isinstance(version_code, int) or isinstance(version_code, bool) or version_code <= 0:
        raise ValueError(f"versionCode must be a positive integer, got: {version_code}")

    version_name = meta["versionName"]
    if not isinstance(version_name, str) or not version_name.strip() or version_name != version_name.strip():
        raise ValueError(f"versionName must be non-empty string without whitespace: '{version_name}'")

    tag_name = meta["tagName"]
    if not isinstance(tag_name, str) or not TAG_REGEX.match(tag_name):
        raise ValueError(f"Invalid tagName format: '{tag_name}'")

    if tag_name != f"v{version_name}":
        raise ValueError(f"tagName ('{tag_name}') must equal 'v' + versionName ('v{version_name}')")

    min_sdk = meta["minSdk"]
    target_sdk = meta["targetSdk"]
    if not isinstance(min_sdk, int) or isinstance(min_sdk, bool) or min_sdk <= 0:
        raise ValueError(f"minSdk must be positive integer: {min_sdk}")
    if not isinstance(target_sdk, int) or isinstance(target_sdk, bool) or target_sdk <= 0:
        raise ValueError(f"targetSdk must be positive integer: {target_sdk}")

    cert_sha = meta["signingCertSha256"]
    if not isinstance(cert_sha, str) or not SHA256_HEX_REGEX.match(cert_sha):
        raise ValueError(f"signingCertSha256 must be 64 lowercase hex characters: '{cert_sha}'")

    apks = meta["apks"]
    if not isinstance(apks, list) or len(apks) != 1:
        raise ValueError("apks must be an array of exactly 1 object")

    apk_entry = apks[0]
    if not isinstance(apk_entry, dict):
        raise ValueError("apks[0] must be an object")

    for k in ("abi", "name", "size", "sha256"):
        if k not in apk_entry or apk_entry[k] is None:
            raise ValueError(f"Missing field in apks[0]: {k}")

    if apk_entry["abi"] != EXPECTED_ABI:
        raise ValueError(f"apks[0].abi must be '{EXPECTED_ABI}', got '{apk_entry['abi']}'")

    apk_name = apk_entry["name"]
    expected_apk_name = canonical_apk_name(tag_name, EXPECTED_ABI)
    if apk_name != expected_apk_name:
        raise ValueError(f"apks[0].name ('{apk_name}') != canonical APK name ('{expected_apk_name}')")

    apk_size = apk_entry["size"]
    if not isinstance(apk_size, int) or isinstance(apk_size, bool) or apk_size <= 0:
        raise ValueError(f"apks[0].size must be a positive integer, got: {apk_size}")

    apk_sha = apk_entry["sha256"]
    if not isinstance(apk_sha, str) or not SHA256_HEX_REGEX.match(apk_sha):
        raise ValueError(f"apks[0].sha256 must be 64 lowercase hex characters: '{apk_sha}'")


def main() -> int:
    apk_path = None
    tag_name = os.environ.get("VERSION_NAME") or os.environ.get("TAG_NAME")
    version_name = os.environ.get("SEMVER")
    version_code_raw = os.environ.get("VERSION_CODE")
    output_path = METADATA_ASSET_NAME
    repo = os.environ.get("GITHUB_REPOSITORY", EXPECTED_REPOSITORY)
    app_id = None
    min_sdk = None
    target_sdk = None
    signing_cert_sha256 = None

    for arg in sys.argv[1:]:
        if arg.startswith("--apk="):
            apk_path = arg.split("=", 1)[1].strip()
        elif arg.startswith("--tag-name="):
            tag_name = arg.split("=", 1)[1].strip()
        elif arg.startswith("--version-name="):
            version_name = arg.split("=", 1)[1].strip()
        elif arg.startswith("--version-code="):
            version_code_raw = arg.split("=", 1)[1].strip()
        elif arg.startswith("--output="):
            output_path = arg.split("=", 1)[1].strip()
        elif arg.startswith("--repo="):
            repo = arg.split("=", 1)[1].strip()
        elif arg.startswith("--app-id="):
            app_id = arg.split("=", 1)[1].strip()
        elif arg.startswith("--min-sdk="):
            min_sdk = int(arg.split("=", 1)[1].strip())
        elif arg.startswith("--target-sdk="):
            target_sdk = int(arg.split("=", 1)[1].strip())
        elif arg.startswith("--signing-cert-sha256="):
            signing_cert_sha256 = arg.split("=", 1)[1].strip()

    if not apk_path:
        # Auto-discover APK in common directories
        for candidate in (
            "release-assets/app-release.apk",
            "android/app/build/outputs/apk/release/app-release.apk",
        ):
            if os.path.isfile(candidate):
                apk_path = candidate
                break

    if not apk_path or not os.path.isfile(apk_path):
        print(f"ERROR: Target APK file not found (specified: {apk_path})", file=sys.stderr)
        return 1

    if not tag_name:
        print("ERROR: --tag-name or VERSION_NAME environment variable is required", file=sys.stderr)
        return 1

    if not tag_name.startswith("v"):
        tag_name = f"v{tag_name}"

    if not version_name:
        version_name = tag_name.lstrip("v")

    version_code = None
    if version_code_raw:
        try:
            version_code = int(version_code_raw)
        except ValueError:
            pass

    if not version_code or version_code <= 0:
        # Fallback to compute_version_code from scripts.release_version if available
        try:
            from scripts.release_version import compute_version_code

            version_code = compute_version_code()
        except Exception:
            version_code = 1

    # App build config defaults from android/app/build.gradle.kts
    gradle_cfg = parse_build_gradle_config()
    final_app_id = app_id or gradle_cfg["applicationId"]
    final_min_sdk = min_sdk if min_sdk is not None else gradle_cfg["minSdk"]
    final_target_sdk = target_sdk if target_sdk is not None else gradle_cfg["targetSdk"]

    try:
        meta = generate_metadata_dict(
            tag_name=tag_name,
            version_name=version_name,
            version_code=version_code,
            apk_path=apk_path,
            repository=repo,
            application_id=final_app_id,
            min_sdk=final_min_sdk,
            target_sdk=final_target_sdk,
            signing_cert_sha256=signing_cert_sha256,
        )
    except Exception as exc:
        print(f"ERROR: Failed to generate valid release metadata: {exc}", file=sys.stderr)
        return 1

    out_dir = os.path.dirname(output_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    json_str = json.dumps(meta, indent=2) + "\n"
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(json_str)

    print(f"Successfully generated {output_path}:")
    print(json_str)
    return 0


if __name__ == "__main__":
    sys.exit(main())
