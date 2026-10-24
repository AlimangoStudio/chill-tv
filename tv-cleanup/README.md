# TV cleanup: remove the ads and bloat before installing Chill TV

Android TVs and boxes ship with ad services, telemetry and preloaded apps. This folder switches them off over Wi-Fi, one tested list per TV model:
- **No root, no cable.**
- **Nothing is uninstalled:** every package is *disabled* (`pm disable-user`), so a restore script undoes everything.

The tools and the **261 model lists** (TCL, Sony, Xiaomi / Mi Box, Philips, Hisense, Skyworth, Amazon Fire TV, Google Chromecast, NVIDIA Shield, onn, Panasonic, Sharp, Toshiba and more) come from [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin, MIT licensed ([LICENSE-cleanyourtv](LICENSE-cleanyourtv)). They are copied here unchanged at upstream commit `eb0131a` ([.upstream-commit](.upstream-commit)). New models are added there first, so check upstream for a newer list.

**LG and Samsung TVs** run webOS and Tizen, not Android. Neither these tools nor Chill TV work on them. Upstream's [SHOWCASE.md](https://github.com/Gathin23/cleanyourtv/blob/main/SHOWCASE.md) links community webOS projects. [docs/devices/samsung-tizen.md](docs/devices/samsung-tizen.md) explains what a Samsung allows.

## Order matters

1. **Install Chill TV first and make it Home** (see the main [README](../README.md)). The disable script will not switch off the stock launcher while nothing else can answer the Home button.
2. **Find your TV** in [`examples/`](examples/). The folder names are `brand-model`, and each `debloat.txt` header gives the exact model, Android version and firmware it was tested on. If yours isn't there, start from the closest model of the same brand, and comment out (`#`) anything you are unsure of.
3. **Read the list and comment out what you use.** For example, keep Chromecast if you cast from your phone. Some models have a `debloat-keep-cast.txt` that keeps casting on.

## Run it

You need Python 3 and the TV's IP address. On the TV, turn on Developer options → USB debugging, plus network or ADB debugging if your TV shows it. Accept "Allow USB debugging?" on the TV the first time.

**Mac / Linux**

```sh
cd tv-cleanup
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
TV_HOST=192.168.1.50 tools/disable.sh examples/xiaomi-mi-box-s/debloat.txt
# undo everything:
TV_HOST=192.168.1.50 tools/restore.sh examples/xiaomi-mi-box-s/debloat.txt
```

**Windows (PowerShell)**

```powershell
cd tv-cleanup
python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
$env:TV_HOST = '192.168.1.50'; .\tools\disable.ps1 examples\xiaomi-mi-box-s\debloat.txt
# undo everything:
.\tools\restore.ps1 examples\xiaomi-mi-box-s\debloat.txt
```

- **Before changing anything**, the scripts save a recovery record in your home folder (`~/.cleanyourtv/`).
- **They refuse any list that would disable core Android packages**, because a TV without them may not boot again.
- **Check a list without changing anything:** `tools/check.py <list>` compares it with what is on the TV.

If something goes wrong, see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). Model-specific notes are in [docs/devices/](docs/devices/).

When you're done, turn USB debugging off again unless you use the Chill TV remote's Computer mode, which needs it.
