#!/usr/bin/env python3
"""Check the inspected APK against the update manifest and existing demo signing key.

Run apksigner verify first: this script compares its verified certificate output, not the
certificate bytes inside an unverified APK. A future signing-key migration must explicitly
update this pinned fingerprint and address upgrades from installed demo-signed versions.
"""

import argparse
import json
import re
from pathlib import Path

PACKAGE_NAME = "com.nchat.app"
DEMO_SIGNER_SHA256 = "429c0bb9664496faaa275638852d4e6b366bfb203c4e5c6d6b60101106e67912"


def verify(badging: str, signer_output: str, manifest: dict) -> None:
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), None)
    if package_line is None:
        raise ValueError("APK badging has no package line")
    fields = dict(re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", package_line))
    if (fields.get("name") != PACKAGE_NAME
            or fields.get("versionCode") != str(manifest["versionCode"])
            or fields.get("versionName") != manifest["versionName"]):
        raise ValueError("APK package or version does not match version.json")

    expected_tag = f"nchat-v{manifest['versionName']}"
    if manifest.get("releaseTag") != expected_tag:
        raise ValueError("releaseTag must match the versionName")
    expected_url = (
        "https://github.com/cybereagle472/NChat-kotlin/releases/download/"
        f"{expected_tag}/nchat-ui-lab.apk"
    )
    if manifest.get("apkUrl") != expected_url:
        raise ValueError("apkUrl must use the exact versioned release asset")
    if manifest.get("signingCertSha256", "").replace(":", "").lower() != DEMO_SIGNER_SHA256:
        raise ValueError("version.json signing fingerprint does not match the pinned release key")

    signers = re.findall(
        r"^Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$",
        signer_output,
        flags=re.MULTILINE,
    )
    if len(signers) != 1 or signers[0][1].replace(":", "").lower() != DEMO_SIGNER_SHA256:
        raise ValueError("APK must have exactly the expected demo signing certificate")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("badging", type=Path)
    parser.add_argument("signer_output", type=Path)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        verify(args.badging.read_text(), args.signer_output.read_text(), json.loads(args.manifest.read_text()))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"Release verification failed: {error}\n")
    print("APK package, version and signing certificate match the expected update.")


if __name__ == "__main__":
    main()
