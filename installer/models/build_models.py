"""Build the TV model catalog for the Chill TV kit and the alimango.app download page.

Usage: python -I build_models.py <cleanyourtv-checkout> <out-dir>

Source: CleanYourTV (https://github.com/Gathin23/cleanyourtv, MIT), one folder per TV model in examples/ with tv.json
(name, OS, brand, notes) and debloat*.txt lists checked on that model. This copies every model's lists and writes
models.json with what the Chill TV helper and the download page need:

- platform: google-tv, android-tv, fire-tv or android (plain Android smart TV);
- fit: "home" (Chill TV can be the home screen) or "apps" (Fire TV: it installs and opens from Apps, Amazon keeps Home);
- pairing_may_be_needed: Android 11+ or Google TV, where Wi-Fi debugging may need a pairing code first (many boxes,
  such as the Mi Box S on Android 12, still connect without one; the helper tries that first);
- home_packages: the TV's own launcher packages in that model's list (what "make it stick" switches off);
- lists: the model's cleanup lists, every entry a bare package name (a list with anything else is dropped, because
  the names go into a shell command on the TV).
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE = re.compile(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
LAUNCHER = re.compile(r'(tvlauncher|launcherx|leanbacklauncher|tvhome|setupwraith|\.launcher$|homescreen)', re.I)
FIELD_MAX = 600


def packages(text: str):
    """The package names in a debloat list, or None when any entry is not a bare package name."""
    out = []
    for raw in text.lstrip('﻿').splitlines():
        line = raw.split('#', 1)[0].strip()
        if not line:
            continue
        if not PACKAGE.match(line):
            return None
        out.append(line)
    return out


def android_version(os_name: str):
    nums = [int(n) for n in re.findall(r'(?:Android(?: TV)?|Google TV)\D{0,3}(\d{1,2})\b', os_name)]
    return max(nums) if nums else None


def platform(os_name: str, brand: str) -> str:
    if 'fire os' in os_name.lower() or brand.lower() == 'amazon':
        return 'fire-tv'
    if 'google tv' in os_name.lower():
        return 'google-tv'
    if 'android tv' in os_name.lower():
        return 'android-tv'
    return 'android'


def clean(text) -> str:
    text = re.sub(r'\s+', ' ', str(text or '')).strip()
    return text[:FIELD_MAX] + ('…' if len(text) > FIELD_MAX else '')


def main(src: Path, out: Path) -> None:
    examples = src / 'examples'
    sha = subprocess.run(['git', '-C', str(src), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    if out.exists():
        shutil.rmtree(out)
    (out / 'lists').mkdir(parents=True)
    models, dropped = [], []
    for folder in sorted(p for p in examples.iterdir() if p.is_dir()):
        meta_path = folder / 'tv.json'
        if not meta_path.is_file():
            continue
        meta = json.loads(meta_path.read_text(encoding='utf-8-sig'))
        name, os_name, brand = clean(meta.get('tv')), clean(meta.get('os')), clean(meta.get('brand'))
        if not name:
            continue
        lists, home = [], set()
        for lst in sorted(folder.glob('debloat*.txt')):
            pk = packages(lst.read_text(encoding='utf-8-sig', errors='replace'))
            if pk is None:
                dropped.append(f'{folder.name}/{lst.name}')
                continue
            dest = out / 'lists' / folder.name
            dest.mkdir(exist_ok=True)
            shutil.copyfile(lst, dest / lst.name)
            lists.append({'file': f'lists/{folder.name}/{lst.name}', 'name': lst.stem, 'count': len(pk)})
            home.update(p for p in pk if LAUNCHER.search(p))
        plat = platform(os_name, brand)
        version = android_version(os_name)
        models.append({
            'id': folder.name,
            'name': name,
            'brand': brand or name.split()[0],
            'os': os_name,
            'notes': clean(meta.get('notes')),
            'platform': plat,
            'fit': 'apps' if plat == 'fire-tv' else 'home',
            'pairing_may_be_needed': plat == 'google-tv' or (version is not None and version >= 11),
            'home_packages': sorted(home),
            'lists': lists,
            'source': f'https://github.com/Gathin23/cleanyourtv/tree/{sha}/examples/{folder.name}',
        })
    models.sort(key=lambda m: (m['brand'].lower(), m['name'].lower()))
    catalog = {
        'source': {'name': 'CleanYourTV', 'url': 'https://github.com/Gathin23/cleanyourtv', 'commit': sha,
                   'license': 'MIT', 'copyright': 'Copyright (c) 2026 Gathin'},
        'count': len(models),
        'brands': len({m['brand'] for m in models}),
        'models': models,
    }
    (out / 'models.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    shutil.copyfile(src / 'LICENSE', out / 'LICENSE-cleanyourtv.txt')
    print(f'{len(models)} models, {catalog["brands"]} brands, source {sha[:9]}; lists dropped (not bare package names): '
          f'{len(dropped)} {dropped[:5]}')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
