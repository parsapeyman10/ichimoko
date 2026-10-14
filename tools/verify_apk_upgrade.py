"""Read-only release gate. Uses Android SDK tools; never accesses signing private keys.

Checks the *built* APK against the previously published APK, not just Gradle source.
The JSON report contains public package/certificate metadata only, safe to publish.
"""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

MAX_APK_BYTES = 200 * 1024 * 1024  # Same ceiling as the installed updater.
REQUIRED_ABIS = {"arm64-v8a", "armeabi-v7a"}


def parse_badging(text):
    package = re.search(r"^package: (.+)$", text, re.M)
    if not package:
        raise ValueError("APK has no package identity")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package.group(1)))

    def sdk(label):
        match = re.search(r"^" + label + r":'(\d+)'$", text, re.M)
        if not match:
            raise ValueError("Missing numeric " + label)
        return int(match.group(1))

    native = re.search(r"^native-code: (.+)$", text, re.M)
    return {
        "applicationId": fields["name"],
        "versionCode": int(fields["versionCode"]),
        "versionName": fields["versionName"],
        "minSdk": sdk("sdkVersion"),
        "targetSdk": sdk("targetSdkVersion"),
        "abis": sorted(re.findall(r"'([^']+)'", native.group(1))) if native else [],
        "debuggable": bool(re.search(r"^application-debuggable(?:\s|$)", text, re.M)),
        "split": fields.get("split", ""),
        "canRequestInstalls": "name='android.permission.REQUEST_INSTALL_PACKAGES'" in text,
    }


def parse_signers(text):
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", text, re.M)
    if not signers:
        raise ValueError("No verified APK content signer")
    return sorted(set(s.lower() for s in signers))


def inspect(apk, aapt, apksigner):
    def run(*args):
        return subprocess.run(args, check=True, capture_output=True, text=True).stdout

    result = parse_badging(run(aapt, "dump", "badging", str(apk)))
    result["signersSha256"] = parse_signers(run(apksigner, "verify", "--verbose", "--print-certs", str(apk)))
    manifest = run(aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml")
    result["testOnly"] = bool(re.search(r"android:testOnly[^\n]*(?:0xffffffff|\btrue\b)", manifest))
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK ZIP integrity check failed")
        result["torAbis"] = sorted({n.split('/')[1] for n in archive.namelist()
                                     if re.fullmatch(r"lib/[^/]+/libtor\.so", n)})
        names = archive.namelist()
        native_abis = sorted({n.split('/')[1] for n in names if re.fullmatch(r"lib/[^/]+/[^/]+\.so", n)})
        if native_abis != result["abis"]:
            raise ValueError("APK native entries disagree with manifest badging")
        result["embeddedProxyEntries"] = sorted(n for n in names if
            Path(n).name.lower() in {"libtor.so", "libtorjni.so", "libgojni.so", "libobfs4.so", "liblyrebird.so", "libsnowflake.so", "snowflake-bridges.txt"})
        result["embeddedProxyDex"] = any(
            any(marker in archive.read(n) for marker in (b"Lorg/torproject/jni/", b"LIPtProxy/", b"Lnet/freehaven/tor/control/"))
            for n in names if re.fullmatch(r"classes[0-9]*\.dex", n))
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    result.update(sha256=digest.hexdigest(), sizeBytes=apk.stat().st_size)
    return result


def verify(previous, candidate, *, certificate, version_code, version_name, require_newer):
    for item in (previous, candidate):
        if item["applicationId"] != "com.aurum.edge":
            raise ValueError("Wrong applicationId: cannot replace the installed release")
        if item["signersSha256"] != [certificate]:
            raise ValueError("APK content signers must exactly match the pinned installed channel")
    if candidate["versionCode"] != version_code or candidate["versionName"] != version_name:
        raise ValueError("Built APK version does not match release metadata")
    if require_newer and candidate["versionCode"] <= previous["versionCode"]:
        raise ValueError("An in-app upgrade must have a strictly higher versionCode")
    if candidate["debuggable"] or candidate["testOnly"] or candidate["split"]:
        raise ValueError("Only a standalone, non-debuggable, non-test APK can be published")
    if candidate["minSdk"] > previous["minSdk"] or candidate["minSdk"] > 26:
        raise ValueError("Minimum Android version increased: existing devices could be excluded")
    if candidate["targetSdk"] < previous["targetSdk"] or candidate["targetSdk"] != 35:
        raise ValueError("Unexpected targetSdk change")
    # An APK with no native libraries is architecture-independent. If JNI remains,
    # it must retain the old ARM coverage. inspect() cross-checks actual ZIP entries.
    if candidate["abis"] and not (set(previous["abis"]) | REQUIRED_ABIS).issubset(candidate["abis"]):
        raise ValueError("New APK drops supported phone CPU architectures")
    if version_code >= 671:
        if candidate["torAbis"] or candidate.get("embeddedProxyEntries") != [] or candidate.get("embeddedProxyDex") is not False:
            raise ValueError("Embedded proxy code/assets must be absent from the lightweight release")
    elif not REQUIRED_ABIS.issubset(candidate["torAbis"]):
        raise ValueError("Embedded Tor native library missing for a supported phone ABI")
    if not candidate["canRequestInstalls"]:
        raise ValueError("New APK cannot request the next in-app update")
    if not 0 < candidate["sizeBytes"] <= MAX_APK_BYTES:
        raise ValueError("APK exceeds the installed updater's size limit")
    return {
        "status": "verified-upgrade" if require_newer else "validation-only",
        "previous": previous,
        "candidate": candidate,
        "requiresAndroidInstallerConfirmation": True,
        "physicalDeviceInstallTested": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("previous", "candidate", "aapt", "apksigner", "certificate", "version-name", "output"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--require-newer", action="store_true")
    args = parser.parse_args()
    certificate = args.certificate.strip().lower()
    if not re.fullmatch(r"[a-f0-9]{64}", certificate):
        parser.error("Invalid certificate pin")
    result = verify(inspect(Path(args.previous), args.aapt, args.apksigner),
                    inspect(Path(args.candidate), args.aapt, args.apksigner),
                    certificate=certificate, version_code=args.version_code,
                    version_name=args.version_name, require_newer=args.require_newer)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"APK compatibility: {result['status']}, code {result['previous']['versionCode']} -> {args.version_code}")


if __name__ == "__main__":
    main()
