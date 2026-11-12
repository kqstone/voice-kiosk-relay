#!/usr/bin/env python3
"""
Compute the next release version name and version code for voice-kiosk-relay.

Rules:
- Queries the latest GitHub release tag via gh api or GitHub REST API.
- If no previous release exists or API is unreachable, starts at v0.1.0.
- Otherwise parses major.minor.patch and increments patch to produce v<major>.<minor>.<patch>.
- Computes version_code as git rev-list --count HEAD.
- Writes version_name, version_code, and semver to GITHUB_OUTPUT and stdout.
"""

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Optional


def get_latest_release_tag_via_gh(repo: str) -> Optional[str]:
    try:
        cmd = ["gh", "api", f"repos/{repo}/releases/latest", "--jq", ".tag_name"]
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if res.returncode == 0:
            tag = res.stdout.strip()
            if tag:
                return tag
    except Exception:
        pass
    return None


def get_latest_release_tag_via_api(repo: str, token: Optional[str]) -> Optional[str]:
    url = f"https://api.github.com/repos/{repo}/releases/latest"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "voice-kiosk-relay-release-script",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            if response.status == 200:
                data = json.loads(response.read().decode("utf-8"))
                tag = data.get("tag_name")
                if tag and isinstance(tag, str):
                    return tag.strip()
    except urllib.error.HTTPError:
        return None
    except Exception:
        return None
    return None


def resolve_latest_release_tag(repo: str, token: Optional[str]) -> Optional[str]:
    gh_tag = get_latest_release_tag_via_gh(repo)
    if gh_tag:
        return gh_tag
    return get_latest_release_tag_via_api(repo, token)


def compute_next_version(latest_tag: Optional[str]) -> str:
    if not latest_tag:
        return "v0.1.0"
    match = re.match(r"^v?(\d+)\.(\d+)\.(\d+)$", latest_tag.strip())
    if not match:
        return "v0.1.0"
    major = int(match.group(1))
    minor = int(match.group(2))
    patch = int(match.group(3)) + 1
    return f"v{major}.{minor}.{patch}"


def compute_version_code(repo_root: Optional[str] = None) -> int:
    cmd = ["git", "rev-list", "--count", "HEAD"]
    if repo_root:
        cmd = ["git", "-C", repo_root, "rev-list", "--count", "HEAD"]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if res.returncode == 0:
            count_str = res.stdout.strip()
            if count_str.isdigit():
                val = int(count_str)
                if val > 0:
                    return val
    except Exception:
        pass
    return 1


def main() -> int:
    repo = os.environ.get("GITHUB_REPOSITORY", "kqstone/voice-kiosk-relay")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")

    for arg in sys.argv[1:]:
        if arg.startswith("--repo="):
            repo = arg.split("=", 1)[1].strip()

    latest_tag = resolve_latest_release_tag(repo, token)
    version_name = compute_next_version(latest_tag)
    version_code = compute_version_code()
    semver = version_name.lstrip("v")

    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            f.write(f"version_name={version_name}\n")
            f.write(f"version_code={version_code}\n")
            f.write(f"semver={semver}\n")

    print(f"version_name={version_name}")
    print(f"version_code={version_code}")
    print(f"semver={semver}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
