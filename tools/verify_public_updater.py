"""Anonymous, retryable audit of the actual published APK (no GitHub token needed)."""
import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path
from verify_apk_upgrade import MAX_APK_BYTES, inspect, verify


def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        'User-Agent': 'AurumEdge-public-updater-audit', 'Cache-Control': 'no-cache',
        'Accept': 'application/json, application/octet-stream',
    }), timeout=40)


def get_json(url):
    with request(url) as response:
        body = response.read(262145)
        if len(body) > 262144:
            raise ValueError('Public JSON is too large')
        return json.loads(body)


def audit(repo, version, code, certificate, output, sdk):
    tag = 'v' + version
    name = 'AurumEdge-' + tag + '.apk'
    releases = get_json(f'https://api.github.com/repos/{repo}/releases?per_page=10')
    release = next((r for r in releases if r['tag_name'] == tag and not r['draft'] and not r['prerelease']), None)
    if release is None:
        raise ValueError('New release not yet visible in anonymous updater list')
    base = f'https://github.com/{repo}/releases/download/{tag}/'
    assets = {a['name']: a for a in release['assets']}
    for item in (name, name + '.json', 'upgrade-compatibility.json'):
        if assets.get(item, {}).get('browser_download_url') != base + item:
            raise ValueError('Required public asset missing or wrong URL: ' + item)
    metadata = get_json(base + name + '.json')
    report = get_json(base + 'upgrade-compatibility.json')
    if (metadata['applicationId'] != 'com.aurum.edge' or metadata['versionCode'] != code
            or metadata['versionName'] != version or metadata['artifactName'] != name
            or metadata['apkUrl'] != base + name or metadata['signerSha256'] != certificate
            or metadata['commitSha'] != release['target_commitish']):
        raise ValueError('Sidecar identity/version/URL/signer/commit mismatch')
    if (assets[name].get('digest') != 'sha256:' + metadata['sha256']
            or assets[name]['size'] != metadata['sizeBytes']):
        raise ValueError('GitHub asset digest/size not yet consistent with sidecar')
    if report['status'] != 'verified-upgrade' or report['candidate']['sha256'] != metadata['sha256']:
        raise ValueError('Upgrade report does not describe published APK')
    if not 0 < metadata['sizeBytes'] <= MAX_APK_BYTES:
        raise ValueError('APK is outside updater size limit')
    apk = output / name
    digest = hashlib.sha256()
    size = 0
    with request(base + name) as response, apk.open('wb') as stream:
        for chunk in iter(lambda: response.read(1024 * 1024), b''):
            size += len(chunk)
            if size > metadata['sizeBytes']:
                raise ValueError('Downloaded APK exceeds expected size')
            digest.update(chunk)
            stream.write(chunk)
    if size != metadata['sizeBytes'] or digest.hexdigest() != metadata['sha256']:
        raise ValueError('Anonymous APK download hash/size mismatch')
    actual = inspect(apk, str(sdk / 'aapt'), str(sdk / 'apksigner'))
    verified = verify(report['previous'], actual, certificate=certificate,
                      version_code=code, version_name=version, require_newer=True)
    (output / 'public-updater-verification.json').write_text(json.dumps(verified, indent=2) + '\n')
    print(f"::notice title=Anonymous signed APK verified::{tag} code={code} bytes={size} SHA-256={digest.hexdigest()} signer={certificate}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('repo', 'version', 'certificate', 'output', 'sdk'):
        parser.add_argument('--' + arg, required=True)
    parser.add_argument('--code', type=int, required=True)
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    # Publishing can race the anonymous Release API / asset digest caches. A download
    # failure remains a hard failure, but not on the very first eventually-consistent read.
    for attempt in range(1, 7):
        try:
            audit(args.repo, args.version, args.code, args.certificate, output, Path(args.sdk))
            return
        except Exception as error:
            print(f'Public updater verification attempt {attempt}/6: {type(error).__name__}: {error}', flush=True)
            if attempt == 6:
                raise
            time.sleep(10)


if __name__ == '__main__':
    main()
