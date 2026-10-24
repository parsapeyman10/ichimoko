"""Replay the in-app updater's decision rules outside the phone.

`AppUpdateRepository` accepts an update only if the public channel passes every gate:
asset name/URL trust, sidecar metadata, version code greater than the installed one,
GitHub digest or sidecar SHA-256, size, downloadable HTTPS URL, and the pinned signer.
When a phone reports "no update found" or an error, this tool answers which gate failed
for a given installed version, using the same endpoints the app calls.

Read-only: it downloads JSON only. It never touches signing keys and never installs.

Examples
    python3 tools/diagnose_in_app_update.py --installed-code 671
    python3 tools/diagnose_in_app_update.py --installed-code 672 --sidecar path/to/AurumEdge-vX.apk.json
"""
import argparse
import json
import urllib.error
import urllib.request
from pathlib import Path
import re
import sys

REPO = 'parsapeyman10/ichimoko'


def pinned_certificate():
    """Pinned updater certificate, read from the repository file when available."""
    path = Path(__file__).resolve().parent.parent / 'update' / 'updater-signer.sha256'
    if path.is_file():
        return path.read_text().strip()
    return '137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04'


CERTIFICATE = pinned_certificate()
DOWNLOADS = f'https://github.com/{REPO}/releases/download/'
MANIFEST_URL = f'https://raw.githubusercontent.com/{REPO}/main/update/aurum-edge.json'
RELEASE_API = f'https://api.github.com/repos/{REPO}/releases?per_page=10'

RELEASE_APK_NAME = re.compile(r'^AurumEdge-v\d+(?:\.\d+)*\.apk$')
RELEASE_JSON_NAME = re.compile(r'^AurumEdge-v\d+(?:\.\d+)*\.apk\.json$')
SHA256 = re.compile(r'^[0-9a-fA-F]{64}$')
SEMANTIC_VERSION = re.compile(r'(?<!\d)(\d+)(?:\.(\d+))(?:\.(\d+))?(?:\.(\d+))?')


def normalize_sha256(value):
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    for prefix in ('sha256:', 'SHA256:'):
        if normalized.startswith(prefix):
            normalized = normalized[len(prefix):].strip()
    return normalized.lower() if SHA256.match(normalized) else None


def trusted_release_asset(url, name):
    return (bool(RELEASE_APK_NAME.match(name)) and url.startswith(DOWNLOADS)
            and url.rsplit('/', 1)[-1] == name and '?' not in url and '#' not in url)


def trusted_metadata_asset(url, name):
    return (bool(RELEASE_JSON_NAME.match(name)) and url.startswith(DOWNLOADS)
            and url.rsplit('/', 1)[-1] == name and '?' not in url and '#' not in url)


def semantic_version(value):
    match = SEMANTIC_VERSION.search(value or '')
    return [int(part) for part in match.groups() if part] if match else None


def compare_versions(left, right):
    for index in range(max(len(left), len(right))):
        result = (left[index] if index < len(left) else 0) - (right[index] if index < len(right) else 0)
        if result:
            return result
    return 0


def version_rank(info):
    if info.get('versionCode') is not None:
        return info['versionCode'] * 1_000_000
    parts = semantic_version(info.get('versionName') or '') or []
    rank = 0
    for part in parts:
        rank = rank * 1_000 + part
    return rank


def fetch_json(url, timeout=40, cache=None):
    """Return (payload, error). Cache maps URL -> file path to keep runs reproducible offline."""
    if cache and url in cache:
        return json.loads(Path(cache[url]).read_text()), None
    if url.startswith('file://'):
        return json.loads(Path(url[7:]).read_text()), None
    request = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json, application/json',
        'User-Agent': 'AurumEdge-diagnose',
        'Cache-Control': 'no-cache',
    })
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(1_048_577)
        if len(body) > 1_048_576:
            return None, 'پاسخ بزرگ‌تر از حد مجاز است'
        return json.loads(body), None
    except urllib.error.HTTPError as error:
        hint = ''
        if error.code == 403 and error.headers.get('X-RateLimit-Remaining') == '0':
            hint = ' (سهمیهٔ بی‌توکن GitHub API تمام شده؛ اپ تا ریست سهمیه چیزی پیدا نمی‌کند)'
        elif error.code == 404:
            hint = ' (مسیر پیدا نشد؛ ممکن است اتصال به این دامنه در شبکهٔ گوشی بسته باشد)'
        return None, f'HTTP {error.code}{hint}'
    except Exception as error:  # noqa: BLE001 - a network failure is a diagnostic result
        return None, f'{type(error).__name__}: {error}'


def check_manifest(manifest, installed_code, installed_package, errors):
    """Mirror of AppUpdateRepository.checkManifest()."""
    if manifest is None:
        return None
    version_code = manifest.get('versionCode')
    if not isinstance(version_code, int):
        errors.append('manifest: versionCode عددی نیست')
        return None
    if version_code <= installed_code:
        return None
    application_id = manifest.get('applicationId')
    if application_id and application_id != installed_package:
        return None
    artifact_name = (manifest.get('artifactName') or '').strip()
    apk_url = (manifest.get('apkUrl') or '').strip()
    if not trusted_release_asset(apk_url, artifact_name):
        apk_url = None
    return {
        'source': 'manifest',
        'versionName': manifest.get('versionName') or str(version_code),
        'versionCode': version_code,
        'downloadUrl': apk_url,
        'expectedSha256': normalize_sha256(manifest.get('sha256')),
        'artifactName': artifact_name or None,
        'sizeBytes': manifest.get('sizeBytes') if isinstance(manifest.get('sizeBytes'), int) else None,
    }


def check_release(release, installed_code, installed_name, fetch_sidecar):
    """Mirror of AppUpdateRepository.checkLatestRelease() for one release."""
    if release.get('draft') or release.get('prerelease'):
        return None, 'draft/prerelease'
    tag = release.get('tag_name') or ''
    assets = release.get('assets') or []
    asset = next((a for a in assets if trusted_release_asset(a.get('browser_download_url', ''),
                                                              a.get('name', ''))), None)
    if asset is None:
        return None, 'هیچ asset با نام AurumEdge-v*.apk و نشانی مورد اعتماد ندارد'
    artifact_name = asset['name']
    sidecar = next((a for a in assets
                    if a.get('name') == artifact_name + '.json'
                    and trusted_metadata_asset(a.get('browser_download_url', ''), a.get('name', ''))), None)
    metadata, sidecar_note = None, None
    if sidecar is not None:
        metadata, sidecar_note = fetch_sidecar(sidecar['browser_download_url'])
    metadata_code = metadata.get('versionCode') if isinstance(metadata, dict) else None
    if metadata_code is not None:
        if metadata_code <= installed_code:
            return None, f'versionCode متادیتا {metadata_code} از نصب‌شده بیشتر نیست'
    else:
        display = tag or artifact_name
        if installed_name and semantic_version(display) and compare_versions(semantic_version(display),
                                                                            semantic_version(installed_name)) <= 0:
            return None, 'تگ از نسخهٔ نصب‌شده جدیدتر نیست'
    return {
        'source': 'release',
        'tagName': tag,
        'assetName': artifact_name,
        'versionName': (metadata or {}).get('versionName') or tag,
        'versionCode': metadata_code,
        'downloadUrl': asset['browser_download_url'],
        'expectedSha256': normalize_sha256(asset.get('digest')) or normalize_sha256((metadata or {}).get('sha256')),
        'sizeBytes': asset.get('size') or (metadata or {}).get('sizeBytes'),
        'sidecarPresent': sidecar is not None,
        'sidecarNote': sidecar_note,
        'assetDigest': asset.get('digest'),
        'assetSize': asset.get('size'),
        'sidecarSha256': normalize_sha256((metadata or {}).get('sha256')),
        'sidecarSize': (metadata or {}).get('sizeBytes'),
        'signerSha256': (metadata or {}).get('signerSha256'),
    }, None


def choose_update(manifest, release):
    """Mirror of AppUpdateRepository.chooseUpdate()."""
    downloadable = [candidate for candidate in (manifest, release) if candidate and candidate.get('downloadUrl')]
    if downloadable:
        return max(downloadable, key=lambda candidate: (version_rank(candidate),
                                                        candidate['source'] == 'manifest'))
    return manifest or release


def diagnose(repo, installed_code, installed_name, installed_package, manifest_url, release_api,
             sidecar_files):
    report = {'installed': {'versionCode': installed_code, 'versionName': installed_name,
                            'packageName': installed_package}, 'checks': {}, 'errors': []}
    manifest, manifest_error = fetch_json(manifest_url)
    report['checks']['manifest'] = {'url': manifest_url, 'error': manifest_error}
    releases, release_error = fetch_json(release_api)
    report['checks']['releases'] = {'url': release_api, 'error': release_error}

    def fetch_sidecar(url):
        if url in sidecar_files:
            return json.loads(Path(sidecar_files[url]).read_text()), 'local file'
        payload, error = fetch_json(url, cache=sidecar_files)
        return payload, error

    manifest_info = check_manifest(manifest, installed_code, installed_package, report['errors'])
    report['checks']['manifest']['usable'] = manifest_info is not None
    if manifest_info:
        report['checks']['manifest']['offers'] = f"{manifest_info['versionName']} ({manifest_info['versionCode']})"

    release_info, reasons = None, []
    per_release = []
    for release in (releases or []):
        info, reason = check_release(release, installed_code, installed_name, fetch_sidecar)
        per_release.append({'tag': release.get('tag_name'), 'offers': bool(info), 'reason': reason})
        if info and release_info is None:
            release_info = info
    report['checks']['releases']['evaluated'] = per_release
    report['checks']['releaseCandidate'] = release_info
    if release_error:
        report['errors'].append(f'release API: {release_error}')

    chosen = choose_update(manifest_info, release_info)
    report['chosen'] = chosen
    if chosen is None:
        if manifest_error and release_error:
            report['verdict'] = 'CHECK_FAILED'
            report['answer'] = ('هر دو کانال پاسخ ندادند؛ اپ پیام «بررسی کامل بروزرسانی انجام نشد» را نشان می‌دهد. '
                                'اینترنت/VPN سیستم و دسترسی به api.github.com و raw.githubusercontent.com را بررسی کنید.')
        else:
            report['verdict'] = 'NO_UPDATE_FOUND'
            report['answer'] = ('نسخهٔ جدیدتری که همهٔ گیت‌های آپدیتر را رد کند پیدا نشد؛ نصب کنونی '
                                f'{installed_name} ({installed_code}) است.')
        return report

    report['verdict'] = 'UPDATE_AVAILABLE'
    report['answer'] = f"نسخهٔ {chosen['versionName']} ({chosen['versionCode']}) از {chosen['source']} پیشنهاد می‌شود."
    expected_signer = CERTIFICATE
    notes = []
    if chosen.get('signerSha256') and chosen['signerSha256'].lower() != expected_signer:
        notes.append('گواهی اعلام‌شدهٔ متادیتا با گواهی پین‌شدهٔ پروژه یکی نیست.')
    if chosen.get('assetDigest') and chosen.get('sidecarSha256') \
            and normalize_sha256(chosen['assetDigest']) != chosen['sidecarSha256']:
        notes.append('digest گیثاب و sha256 متادیتا یکی نیست؛ اپ دانلود را رد می‌کند.')
    if chosen.get('assetSize') and chosen.get('sidecarSize') and chosen['assetSize'] != chosen['sidecarSize']:
        notes.append('حجم asset و متادیتا یکی نیست؛ اپ دانلود را رد می‌کند.')
    report['notes'] = notes
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--installed-code', type=int, required=True,
                        help='BuildConfig.VERSION_CODE روی گوشی (کد نسخه در تب بروزرسانی)')
    parser.add_argument('--installed-name', default='', help='BuildConfig.VERSION_NAME روی گوشی')
    parser.add_argument('--installed-package', default='com.aurum.edge')
    parser.add_argument('--repo', default=REPO)
    parser.add_argument('--manifest-url', default=MANIFEST_URL)
    parser.add_argument('--release-api', default=RELEASE_API)
    parser.add_argument('--sidecar', action='append', default=[],
                        help='JSON متادیتای محلی؛ به‌جای دانلود از شبکه استفاده می‌شود')
    parser.add_argument('--offline-cache', default='',
                        help='پوشه‌ای که پاسخ‌های شبکه در آن کش می‌شود (برای اجرای تکراری/آفلاین)')
    parser.add_argument('--output', default='')
    args = parser.parse_args()

    sidecar_files = {}
    cache_dir = Path(args.offline_cache) if args.offline_cache else None
    if cache_dir:
        cache_dir.mkdir(parents=True, exist_ok=True)
    for path in args.sidecar:
        payload = json.loads(Path(path).read_text())
        name = payload.get('artifactName')
        if name:
            sidecar_files[DOWNLOADS.replace(REPO, args.repo) + name + '.json'] = path

    if cache_dir:
        for url, filename in ((args.manifest_url, 'manifest.json'), (args.release_api, 'releases.json')):
            cached = cache_dir / filename
            if cached.exists():
                sidecar_files.setdefault(url, str(cached))

    report = diagnose(args.repo, args.installed_code, args.installed_name, args.installed_package,
                      args.manifest_url, args.release_api, sidecar_files)
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        Path(args.output).write_text(text + '\n')
    print(text)
    if cache_dir:
        for url, filename in ((args.manifest_url, 'manifest.json'), (args.release_api, 'releases.json')):
            payload, _ = fetch_json(url)
            if payload is not None:
                (cache_dir / filename).write_text(json.dumps(payload))
    return 0 if report['verdict'] in ('UPDATE_AVAILABLE', 'NO_UPDATE_FOUND') else 1


if __name__ == '__main__':
    sys.exit(main())
