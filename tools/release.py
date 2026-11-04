#!/usr/bin/env python3
# Copyright 2026 Creative Moods, Lda.
# SPDX-License-Identifier: Apache-2.0

"""Validate release identity and sign without exposing a keystore to Gradle."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

PACKAGE = "pt.creativemoods.scorepuff"
APK_NAME = "scorepuff.apk"


def version(source):
    name = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"', source)
    code = re.search(r'versionCode\s*=\s*(\d+)', source)
    if not name or not code or int(code[1]) < 1:
        raise ValueError("Expected a stable versionName and positive versionCode")
    return name[1], int(code[1])


def metadata(source, tag, commit):
    name, code = version(source)
    if tag != "v" + name:
        raise ValueError("Release tag must exactly match v<versionName>")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a full commit SHA")
    return dict(package=PACKAGE, versionName=name, versionCode=code, tag=tag, commit=commit)


def output(command):
    return subprocess.check_output(command, text=True, stderr=subprocess.PIPE)


def verify(apk, info, build_tools, expected_certificate):
    certificate = expected_certificate.read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", certificate):
        raise ValueError("Invalid pinned release certificate")
    report = output([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", report)
    if fingerprints != [certificate]:
        raise ValueError("APK certificate differs from the pinned ScorePuff release key")
    subprocess.run([str(build_tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    badging = output([str(build_tools / "aapt"), "dump", "badging", str(apk)])
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not identity or identity.groups() != (PACKAGE, str(info["versionCode"]), info["versionName"]):
        raise ValueError("APK package/version does not match release metadata")
    if "application-debuggable" in badging:
        raise ValueError("Refusing to release a debuggable APK")
    sensitive = [os.environ.get(name, "").encode() for name in ("ANDROID_STORE_PASSWORD", "ANDROID_KEY_PASSWORD")]
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if entry.endswith((".jks", ".keystore")) or Path(entry).name in ("keystore.properties", "omdb.properties"):
                raise ValueError("Private configuration found in APK")
            if entry.startswith("assets/") and entry.endswith(".xml"):
                raise ValueError("Debug fixture found in release APK")
            data = archive.read(entry)
            if any(value and value in data for value in sensitive):
                raise ValueError("Signing credential found in APK")
    return certificate


def sign(args):
    required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    if any(not os.environ.get(name) for name in required):
        raise ValueError("All four Android signing secrets must be configured")
    info = json.loads(args.metadata.read_text())
    # Validate downloaded metadata independently before handling the private key.
    if (info["package"] != PACKAGE or info["tag"] != "v" + info["versionName"]
            or not re.fullmatch(r"\d+\.\d+\.\d+", info["versionName"])
            or not isinstance(info["versionCode"], int) or info["versionCode"] < 1
            or not re.fullmatch(r"[0-9a-f]{40}", info["commit"])):
        raise ValueError("Invalid release metadata")
    args.destination.mkdir(parents=True, exist_ok=True)
    apk = args.destination / APK_NAME
    encoded = "".join(os.environ["ANDROID_KEYSTORE_BASE64"].split())
    with tempfile.TemporaryDirectory(prefix="scorepuff-signing-", dir=os.environ.get("RUNNER_TEMP")) as private:
        keystore = Path(private) / "release.jks"
        keystore.write_bytes(base64.b64decode(encoded, validate=True))
        keystore.chmod(0o600)
        subprocess.run([str(args.build_tools / "apksigner"), "sign", "--ks", str(keystore),
                        "--ks-key-alias", os.environ["ANDROID_KEY_ALIAS"],
                        "--ks-pass", "env:ANDROID_STORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
                        "--v4-signing-enabled", "false", "--out", str(apk), str(args.apk)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        fingerprint = verify(apk, info, args.build_tools, args.certificate)
    except Exception:
        apk.unlink(missing_ok=True)
        raise
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    info.update(sha256=digest, certificateSha256=fingerprint, sizeBytes=apk.stat().st_size)
    (args.destination / "release.json").write_text(json.dumps(info, indent=2) + "\n")
    (args.destination / (APK_NAME + ".sha256")).write_text(digest + "  " + APK_NAME + "\n")
    print("Signed APK verified: package, version, certificate, alignment and release contents.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--tag", required=True)
    validate.add_argument("--output", type=Path, required=True)
    signing = commands.add_parser("sign")
    signing.add_argument("--apk", type=Path, required=True)
    signing.add_argument("--metadata", type=Path, required=True)
    signing.add_argument("--destination", type=Path, required=True)
    signing.add_argument("--build-tools", type=Path, required=True)
    signing.add_argument("--certificate", type=Path, default=Path(".github/release-certificate.sha256"))
    args = parser.parse_args()
    if args.command == "validate":
        info = metadata(Path("app/build.gradle.kts").read_text(), args.tag,
                        output(["git", "rev-parse", "HEAD"]).strip())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(info, indent=2) + "\n")
        print(f"Validated {info['tag']} (Android versionCode {info['versionCode']})")
    else:
        sign(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        # Never print command output or exception payloads that may contain credentials.
        raise SystemExit("Release validation/signing failed. Check inputs, certificate and signing secrets.")
