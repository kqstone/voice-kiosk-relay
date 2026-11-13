#!/usr/bin/env python3
"""
Unit tests for scripts/generate_release_metadata.py.
Validates that generated release metadata strictly satisfies ReleaseUpdateChecker.kt.
"""

import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest

from scripts.generate_release_metadata import (
    EXPECTED_ABI,
    EXPECTED_APPLICATION_ID,
    EXPECTED_REPOSITORY,
    EXPECTED_SCHEMA_VERSION,
    canonical_apk_name,
    generate_metadata_dict,
    parse_apk_signing_block_der,
    parse_build_gradle_config,
    validate_metadata,
    validate_tag_name,
)


def create_synthetic_signed_apk(cert_bytes: bytes, include_v2: bool = True, include_v3: bool = True) -> bytes:
    """Constructs a minimal valid ZIP/APK binary with an APK Signing Block (v2/v3)."""
    digests = b""
    digests_seq = struct.pack("<I", len(digests)) + digests
    cert_entry = struct.pack("<I", len(cert_bytes)) + cert_bytes
    certs_seq = struct.pack("<I", len(cert_entry)) + cert_entry
    signed_data = digests_seq + certs_seq
    signed_data_entry = struct.pack("<I", len(signed_data)) + signed_data
    signatures = struct.pack("<I", 0)
    public_key = struct.pack("<I", 0)
    signer = signed_data_entry + signatures + public_key
    signer_entry = struct.pack("<I", len(signer)) + signer
    signers = struct.pack("<I", len(signer_entry)) + signer_entry

    pairs = b""
    if include_v2:
        pair_v2 = struct.pack("<Q", len(signers) + 4) + struct.pack("<I", 0x7109871A) + signers
        pairs += pair_v2
    if include_v3:
        pair_v3 = struct.pack("<Q", len(signers) + 4) + struct.pack("<I", 0xF05368C0) + signers
        pairs += pair_v3

    magic = b"APK Sig Block 42"
    block_size = len(pairs) + len(magic) + 8
    block = struct.pack("<Q", block_size) + pairs + struct.pack("<Q", block_size) + magic

    dummy_payload = b"FAKE_ZIP_PAYLOAD_FOR_UNIT_TEST"
    cd = b"FAKE_CENTRAL_DIRECTORY"
    cd_offset = len(dummy_payload) + len(block)
    # EOCD record (22 bytes)
    eocd = b"PK\x05\x06" + struct.pack("<HHHHIIH", 0, 0, 1, 1, len(cd), cd_offset, 0)
    return dummy_payload + block + cd + eocd


class TestGenerateReleaseMetadata(unittest.TestCase):

    def setUp(self):
        self.sample_cert = b"MOCK_DER_X509_CERTIFICATE_VOICE_KIOSK_TEST"
        self.sample_cert_sha256 = hashlib.sha256(self.sample_cert).hexdigest().lower()

    def test_canonical_apk_name(self):
        self.assertEqual(canonical_apk_name("v0.1.7"), "voice-kiosk-v0.1.7-arm64-v8a.apk")
        self.assertEqual(canonical_apk_name("v0.2.0"), "voice-kiosk-v0.2.0-arm64-v8a.apk")
        self.assertEqual(canonical_apk_name("v1.0.0"), "voice-kiosk-v1.0.0-arm64-v8a.apk")

        with self.assertRaises(ValueError):
            canonical_apk_name("0.1.7")  # Missing leading v
        with self.assertRaises(ValueError):
            canonical_apk_name("v0.1.7", abi="armeabi-v7a")  # Non-arm64 ABI
        with self.assertRaises(ValueError):
            canonical_apk_name("v0.1.7/../evil")  # Path traversal

    def test_validate_tag_name(self):
        self.assertEqual(validate_tag_name("v0.1.0"), "v0.1.0")
        self.assertEqual(validate_tag_name("v1.2.3"), "v1.2.3")
        with self.assertRaises(ValueError):
            validate_tag_name("invalid")
        with self.assertRaises(ValueError):
            validate_tag_name("v1.2")
        with self.assertRaises(ValueError):
            validate_tag_name("v1.2.3/..")

    def test_apk_signing_block_v2_v3_parser(self):
        apk_data = create_synthetic_signed_apk(self.sample_cert, include_v2=True, include_v3=True)
        with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as f:
            f.write(apk_data)
            apk_path = f.name

        try:
            extracted_der = parse_apk_signing_block_der(apk_path)
            self.assertEqual(extracted_der, self.sample_cert)
            extracted_sha = hashlib.sha256(extracted_der).hexdigest().lower()
            self.assertEqual(extracted_sha, self.sample_cert_sha256)
        finally:
            os.unlink(apk_path)

    def test_generate_and_validate_metadata_dict(self):
        apk_data = create_synthetic_signed_apk(self.sample_cert)
        with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as f:
            f.write(apk_data)
            apk_path = f.name

        try:
            meta = generate_metadata_dict(
                tag_name="v0.1.7",
                version_name="0.1.7",
                version_code=3,
                apk_path=apk_path,
                repository=EXPECTED_REPOSITORY,
                application_id=EXPECTED_APPLICATION_ID,
                min_sdk=28,
                target_sdk=31,
            )

            # Validate all required fields and their values
            self.assertEqual(meta["schemaVersion"], EXPECTED_SCHEMA_VERSION)
            self.assertEqual(meta["repository"], EXPECTED_REPOSITORY)
            self.assertEqual(meta["applicationId"], EXPECTED_APPLICATION_ID)
            self.assertEqual(meta["versionCode"], 3)
            self.assertEqual(meta["versionName"], "0.1.7")
            self.assertEqual(meta["tagName"], "v0.1.7")
            self.assertEqual(meta["minSdk"], 28)
            self.assertEqual(meta["targetSdk"], 31)
            self.assertEqual(meta["signingCertSha256"], self.sample_cert_sha256)

            self.assertEqual(len(meta["apks"]), 1)
            apk_entry = meta["apks"][0]
            self.assertEqual(apk_entry["abi"], EXPECTED_ABI)
            self.assertEqual(apk_entry["name"], "voice-kiosk-v0.1.7-arm64-v8a.apk")
            self.assertEqual(apk_entry["size"], len(apk_data))
            self.assertEqual(apk_entry["sha256"], hashlib.sha256(apk_data).hexdigest().lower())

            # Ensure validation passes
            validate_metadata(meta)
        finally:
            os.unlink(apk_path)

    def test_validation_rejections(self):
        valid_meta = {
            "schemaVersion": 1,
            "repository": "kqstone/voice-kiosk-relay",
            "applicationId": "com.voicekiosk.app",
            "versionCode": 3,
            "versionName": "0.1.7",
            "tagName": "v0.1.7",
            "minSdk": 28,
            "targetSdk": 31,
            "signingCertSha256": "a" * 64,
            "apks": [
                {
                    "abi": "arm64-v8a",
                    "name": "voice-kiosk-v0.1.7-arm64-v8a.apk",
                    "size": 1024,
                    "sha256": "b" * 64,
                }
            ],
        }
        # Baseline should be valid
        validate_metadata(valid_meta)

        # Missing field
        bad = dict(valid_meta)
        del bad["schemaVersion"]
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Wrong schemaVersion
        bad = dict(valid_meta, schemaVersion=2)
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Wrong repository
        bad = dict(valid_meta, repository="kqstone/voice-kiosk")
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Wrong applicationId
        bad = dict(valid_meta, applicationId="com.other.app")
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Invalid versionCode (<= 0 or float)
        bad = dict(valid_meta, versionCode=0)
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        bad = dict(valid_meta, versionCode=3.5)
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Tag and versionName mismatch
        bad = dict(valid_meta, tagName="v0.1.8")
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Invalid signingCertSha256 (uppercase or not 64 hex)
        bad = dict(valid_meta, signingCertSha256="A" * 64)
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        bad = dict(valid_meta, signingCertSha256="short")
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # apks length != 1
        bad = dict(valid_meta, apks=[])
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Non-canonical APK name in apks[0]
        bad = dict(
            valid_meta,
            apks=[
                {
                    "abi": "arm64-v8a",
                    "name": "app-release.apk",
                    "size": 1024,
                    "sha256": "b" * 64,
                }
            ],
        )
        with self.assertRaises(ValueError):
            validate_metadata(bad)

        # Unsupported ABI
        bad = dict(
            valid_meta,
            apks=[
                {
                    "abi": "x86_64",
                    "name": "voice-kiosk-v0.1.7-arm64-v8a.apk",
                    "size": 1024,
                    "sha256": "b" * 64,
                }
            ],
        )
        with self.assertRaises(ValueError):
            validate_metadata(bad)

    def test_parse_build_gradle_config(self):
        cfg = parse_build_gradle_config("android/app/build.gradle.kts")
        self.assertEqual(cfg["applicationId"], "com.voicekiosk.app")
        self.assertEqual(cfg["minSdk"], 28)
        self.assertEqual(cfg["targetSdk"], 31)

    def test_cli_execution(self):
        apk_data = create_synthetic_signed_apk(self.sample_cert)
        with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as f_apk:
            f_apk.write(apk_data)
            apk_path = f_apk.name

        with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f_out:
            out_path = f_out.name

        try:
            cmd = [
                sys.executable,
                "scripts/generate_release_metadata.py",
                f"--apk={apk_path}",
                "--tag-name=v0.1.7",
                "--version-name=0.1.7",
                "--version-code=7",
                f"--output={out_path}",
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, check=False)
            self.assertEqual(res.returncode, 0, msg=f"CLI failed: {res.stderr}")

            with open(out_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)

            self.assertEqual(loaded["versionName"], "0.1.7")
            self.assertEqual(loaded["tagName"], "v0.1.7")
            self.assertEqual(loaded["versionCode"], 7)
            self.assertEqual(loaded["signingCertSha256"], self.sample_cert_sha256)
            self.assertEqual(loaded["apks"][0]["name"], "voice-kiosk-v0.1.7-arm64-v8a.apk")
            validate_metadata(loaded)
        finally:
            if os.path.exists(apk_path):
                os.unlink(apk_path)
            if os.path.exists(out_path):
                os.unlink(out_path)


if __name__ == "__main__":
    unittest.main()
