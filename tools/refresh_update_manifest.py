#!/usr/bin/env python3
"""Refresh update/aurum-edge.json from the sidecar of a published updater release.

The default-branch manifest is a *copy* of the metadata that ships inside a release. It goes
stale the moment a new release is published, and a stale copy is the one state in which a phone
reads "no newer version" (or, worse, is pointed at an older APK) while a newer APK already
exists. CI refuses that state, so publishing a release and refreshing this file belong together.

Use after ``release: publish``:

    python3 tools/refresh_update_manifest.py --tag v2.0.5
    python3 tools/refresh_update_manifest.py --sidecar out/AurumEdge-v2.0.5.apk.json
    python3 tools/refresh_update_manifest.py --tag v2.0.5 --check   # CI: fail if stale

Only the anonymous, public sidecar is read. No token, no release write, no APK download.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = "parsapeyman10/ichimoko"
MANIFEST = Path("update/aurum-edge.json")
APPLICATION_ID = "com.aurum.edge"
APK_PREFIX = "https://github.com/"
APK_PATH = f"/releases/download/"
# Order matters: it is the order users and the gate read, and a stable diff is easier to review.
KEYS = (
    "applicationId", "versionCode", "versionName", "tagName", "commitSha", "publishedAt",
    "artifactName", "notes", "apkUrl", "sha256", "sizeBytes", "signerSha256",
    "minSdk", "targetSdk", "supportedAbis",
)


class ManifestError(Exception):
    pass


def _is_digest(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdefABCDEF" for c in value)


def build_manifest(sidecar: dict, tag: str, keep_notes: str | None = None) -> dict:
    """Validate a release sidecar and return the default-branch manifest for it.

    [keep_notes] is the hand-written release note already in the manifest. The sidecar carries the
    generic workflow note, and replacing the human text would make the update card tell users less
    than before, so the existing note survives a refresh.
    """
    if not isinstance(sidecar, dict):
        raise ManifestError("sidecar is not a JSON object")
    application_id = sidecar.get("applicationId")
    if application_id != APPLICATION_ID:
        raise ManifestError(f"sidecar applicationId is {application_id!r}, expected {APPLICATION_ID!r}")
    code = sidecar.get("versionCode")
    if not isinstance(code, int) or isinstance(code, bool) or code <= 0:
        raise ManifestError(f"sidecar versionCode is {code!r}, expected a positive integer")
    name = sidecar.get("versionName")
    if not isinstance(name, str) or not name.strip():
        raise ManifestError("sidecar has no versionName")
    artifact = sidecar.get("artifactName") or f"AurumEdge-{tag}.apk"
    if not isinstance(artifact, str) or not artifact.startswith("AurumEdge-v") or not artifact.endswith(".apk"):
        raise ManifestError(f"unexpected artifactName {artifact!r}; the updater only accepts AurumEdge-v*.apk")
    apk_url = sidecar.get("apkUrl")
    if not isinstance(apk_url, str) or not apk_url.startswith(APK_PREFIX) or APK_PATH not in apk_url \
            or not apk_url.endswith("/" + artifact):
        raise ManifestError(f"apkUrl {apk_url!r} is not a trusted release asset of {REPO}")
    if not _is_digest(sidecar.get("sha256")):
        raise ManifestError("sidecar sha256 must be 64 hex characters")
    if not _is_digest(sidecar.get("signerSha256")):
        raise ManifestError("sidecar signerSha256 must be 64 hex characters; the updater needs it")
    size = sidecar.get("sizeBytes")
    if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
        raise ManifestError(f"sidecar sizeBytes is {size!r}, expected a positive integer")

    manifest = dict(sidecar)
    if keep_notes and keep_notes.strip():
        manifest["notes"] = keep_notes.strip()
    manifest["applicationId"] = APPLICATION_ID
    manifest["versionCode"] = code
    manifest["versionName"] = name.strip()
    manifest["tagName"] = tag
    manifest["artifactName"] = artifact
    manifest["apkUrl"] = apk_url
    manifest["sha256"] = sidecar["sha256"].lower()
    manifest["signerSha256"] = sidecar["signerSha256"].lower()
    manifest["sizeBytes"] = size
    # Unknown keys are dropped on purpose: the phone reads a small, known document.
    return {key: manifest[key] for key in KEYS if key in manifest}


def render(manifest: dict) -> str:
    return json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"


def download_sidecar(tag: str, repo: str = REPO, timeout: int = 30) -> dict:
    url = f"https://github.com/{repo}/releases/download/{tag}/AurumEdge-{tag}.apk.json"
    request = urllib.request.Request(url, headers={"User-Agent": "aurum-manifest-refresh"})
    with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - fixed https host
        return json.loads(response.read(1_048_577))


def _existing_notes(path: Path) -> str | None:
    """Read the note already written in the manifest, so a refresh does not flatten it."""
    if not path.exists():
        return None
    try:
        notes = json.loads(path.read_text(encoding="utf-8")).get("notes")
    except ValueError:
        return None
    return notes if isinstance(notes, str) else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--tag", help="published release tag, e.g. v2.0.5 (sidecar is downloaded)")
    source.add_argument("--sidecar", type=Path, help="local sidecar JSON produced by the workflow")
    parser.add_argument("--output", type=Path, default=MANIFEST)
    parser.add_argument("--check", action="store_true",
                        help="do not write; exit 1 when the file is not exactly the release metadata")
    args = parser.parse_args(argv)

    try:
        if args.sidecar:
            sidecar = json.loads(args.sidecar.read_text(encoding="utf-8"))
            tag = (sidecar.get("tagName") or "").strip()
            if not tag:
                # The workflow names the sidecar after its own tag, so the file name is enough.
                file_name = args.sidecar.name
                tag = file_name[len("AurumEdge-"):-len(".apk.json")] \
                    if file_name.startswith("AurumEdge-") and file_name.endswith(".apk.json") else ""
        else:
            tag = args.tag
            sidecar = download_sidecar(tag)
        if not tag:
            raise ManifestError("could not determine the release tag; pass --tag with a local sidecar")
        rendered = render(build_manifest(sidecar, tag, keep_notes=_existing_notes(args.output)))
    except (ManifestError, OSError, ValueError, urllib.error.URLError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
    if args.check:
        if current != rendered:
            print(f"error: {args.output} is not the metadata of {tag}.", file=sys.stderr)
            print(f"       run: python3 tools/refresh_update_manifest.py --tag {tag}", file=sys.stderr)
            return 1
        print(f"{args.output} already matches {tag}.")
        return 0

    if current == rendered:
        print(f"{args.output} already matches {tag}; nothing to write.")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output} for {tag} (versionCode {json.loads(rendered)['versionCode']}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
