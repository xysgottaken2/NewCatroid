# PC games in NewCatroid (Wine + Box64 + Vulkan drivers)

This document describes the PC compatibility layer that adds a *launcher* for
Windows games to NewCatroid: graphics-driver packs, DXVK/VKD3D packs, a
Wine/Box64 runtime, a container, environment variables, a ready-made launcher
dialog and a customizable on-screen gamepad.

It is the same construction Winlator, GameHub, Mobox and Termux-based setups use:
the x86_64 game runs through **Box64** (x86_64 → ARM64 translation) on top of
**Wine** (Win32 API), inside a **proot** container that provides a Linux user
space, with a user supplied **Vulkan driver** (usually Turnip/Mesa for Adreno)
and a **D3D → Vulkan translation layer** (DXVK, VKD3D-Proton, D8VK).

NewCatroid itself ships **no** third-party binaries. Wine, Box64, proot,
Turnip, DXVK and the rootfs are imported by the user as ordinary `.zip` packs,
so nothing GPL/proprietary is bundled into this repository or into an APK that
is built from a project.

How to *build* the four archives (driver, DXVK, runtime, rootfs) is described in
[PC_GAMES_PACKS.md](PC_GAMES_PACKS.md).

---

## 1. What was added

| Area | Code |
| --- | --- |
| Runtime engine | `catroid/src/main/java/org/catrobat/catroid/pcgame/` |
| Pack import / registry | `PcPacks.kt`, `PcPermissions.kt` |
| Launch script and environment | `PcLaunch.kt` (`PcEnvironment`, `PcScriptBuilder`) |
| Orchestration, logging, self test | `PcGameManager.kt` |
| Launcher UI | `PcLauncherDialog.kt` |
| Touch overlay | `PcTouchControls.kt`, `PcTouchControlsActor.kt` |
| File browser (folders + pack archives) | `PcFileBrowser.kt` |
| Environment variable editor | `PcEnvEditorDialog.kt` |
| Online catalogue (GitHub releases, downloads) | `PcOnlinePacks.kt`, `PcOnlinePackDialog.kt` |
| Device memory readings (RAM sensors + blocks) | `utils/DeviceMemory.kt`, `content/actions/PcMemoryActions.kt` |
| Interactive blocks (pickers, editor, downloads) | `content/actions/PcPickActions.kt`, `content/actions/PcOnlineActions.kt` |
| Blocks (28) | `content/bricks/Pc*Brick.java`, `content/actions/Pc*.kt` |
| Display bridge | `StageActivity.attachVncDisplay(...)`, `StageActivity.showPcDisplay(...)`, `StageActivity.setPcTouchOverlay(...)`, `StageListener.setPcTouchControls(...)` |

The game picture and the touch input travel over the **same VNC pipeline the
QEMU blocks already use**: the container starts `Xvfb` plus `x11vnc`, the stage
renders the framebuffer into the existing VM texture, and touches are sent back
as real X11 pointer/key events (x11vnc injects them with XTEST). That means the
existing `key event`, `mouse event` and `move mouse relative` blocks also work
with a running PC game.

---

## 2. Two run modes

The layer picks the mode automatically on every launch:

| Mode | When | What runs |
| --- | --- | --- |
| **proot container** | a `rootfs` pack (or a container folder) is available | `proot -r <rootfs>` → `/bin/sh /pcgame/scripts/launch.sh` → `box64 wine game.exe`, with the game folder, the Wine prefix, the runtime, the driver and the D3D layer bind-mounted to `/game`, `/prefix`, `/runtime`, `/drivers`, `/d3d` |
| **host mode** | no rootfs, but a runtime pack exists | the same script is started by `/system/bin/sh` and uses the real app paths; `box64` is executed through `/system/bin/linker64` (Android 10+ forbids `execve` of files in the app data directory). The runtime pack must then be self contained: `box64`, `wine`, an X server and `x11vnc` in `bin/` |

Both modes produce the same `launch.sh`, which always contains the full
environment, so a bug report is one log dump away. The block "check the PC
environment" prints the mode plus every component it found.

## 3. Install order (once per device)

All four components are optional at the same time - the layer tells you what is
missing (see the "self test" block).

### 2.0 Packs from the internet (easiest - nothing on the phone)

`Download PC components from the internet` opens a catalogue with four tabs and
lists, live from the GitHub API, every release archive of these repositories:

| Tab | Repository | What you get |
| --- | --- | --- |
| Drivers | `K11MCH1/AdrenoToolsDrivers` | Turnip builds (`Turnip_v26.0.0_R8.zip`, `_Gmem`, `_Sysmem`, `_Autotuner`) and the Qualcomm `adpkg` drivers |
| DXVK | `doitsujin/dxvk`, `HansKristian-Work/vkd3d-proton`, `AlpyneDreams/d8vk` | DXVK (D3D 9/10/11), VKD3D-Proton (D3D 12), D8VK (D3D 8) |
| Runtime | `ptitSeb/box64` | Box64 builds and the x86 libs bundle |
| Rootfs | `olegos2/termux-box` | A Debian rootfs prepared for Wine |

Tap an entry → confirm the size → the archive is downloaded into the app cache,
extracted, imported as a normal pack and selected for the next launch. The list
is exactly the same set of packs you would get by downloading the file in a
browser; the pack lives in `files/pcgame/packs/<type>/<id>/` either way, and an
already installed pack is **not** downloaded twice (the importer refuses the
duplicate id and keeps the existing folder).

Above the list, *Installed* shows what is already on the device: tap to select
it, long press to delete it.

* **+ Add repository** - any public GitHub repository that publishes pack
  archives as release assets (a fork, a mirror, another Turnip build). It is
  remembered per category in `files/pcgame/online_repos.json`. Only assets that
  the importer can open are listed (`.zip`, `.tar`, `.tar.gz`, `.tar.xz`,
  `.tzst`); APKs, sources, symbols and checksums are filtered out.
* **⟳ Reload** - re-reads the release lists.

`Download a PC component from the web address [url] as [type]` does the same for
a single direct link (a mirror, `https://.../dxvk-3.1.1.tar.gz`, an
`ubuntu-base-*-arm64.tar.gz`). If the type is left empty it is detected from the
archive content.

Two honest notes:

* The GitHub API allows 60 anonymous requests per hour per address. The list then
  says so instead of failing silently; the fallback is always "download the file
  in a browser and use a *Choose a … zip* block".
* **Wine itself is not in the catalogue.** Ready-made aarch64 Wine builds for
  Android are distributed inside emulator APKs, not as release archives, so the
  runtime tab can only offer Box64. Put your Wine build into the runtime archive
  (see [PC_GAMES_PACKS.md](PC_GAMES_PACKS.md)), or add a repository that
  publishes one with *+ Add repository*.

### 2.1 Container rootfs (`rootfs`)

A folder with a Linux user space: `bin/sh`, `usr/`, `etc/`, `lib/`. Any
Ubuntu/Debian based rootfs that was prepared for proot works. Import it with the
"import PC pack" block (`rootfs`) or with the launcher's *Import pack…* button.

Supported containers: `.zip`, `.tar`, `.tar.gz`/`.tgz`, `.tar.xz`/`.txz` and
`.tzst`/`.tar.zst` (the format Winlator and Mobox distribute their rootfs in).
The format is detected from the magic bytes, so a misnamed file still imports.
Symlinks and hard links inside a tar rootfs are recreated as real links and the
exec bit of `bin/*` is preserved - without that `bin/sh` and the dynamic linker
of the rootfs would be broken.

```
pcgame/packs/rootfs/<id>/bin/sh
pcgame/packs/rootfs/<id>/usr/...
```

The pack may be wrapped in one top-level directory, it is flattened on import.

Two helpers must exist inside the rootfs for the display to work:

* `Xvfb` (X server) - command configured in `xServerCommand`
  (default `Xvfb :0 -screen 0 %RES%x24 -nolisten tcp`),
* `x11vnc` (VNC bridge) - command configured in `vncCommand`
  (default `x11vnc -display :0 -rfbport %PORT% -nopw -forever -shared -noxdamage -repeat -quiet`).

`%RES%` becomes `WIDTHxHEIGHT`, `%PORT%` becomes the configured display port
(5901 by default).

### 2.2 Wine + Box64 runtime (`runtime`)

An aarch64 build of Box64 plus a Wine build (x86_64 Wine libraries). Expected
layout (anything similar works, the loader only looks for `bin/box64`):

```
pcgame/packs/runtime/<id>/bin/box64
pcgame/packs/runtime/<id>/bin/wine
pcgame/packs/runtime/<id>/lib/...
pcgame/packs/runtime/<id>/share/wine/...
```

The directory is bind-mounted at `/runtime` inside the container and
`/runtime/bin` is prepended to `PATH`, `/runtime/lib` to `LD_LIBRARY_PATH`.
Wine is started as `box64 wine ...` (`box64Command` / `wineCommand` can be
changed with environment variables in a `hook_pre.sh`, see 6).

### 2.3 Graphics driver (`driver`)

An AdrenoTools-style Vulkan driver pack (Turnip, Zink, SwiftShader …). The
importer looks for `*icd*.json` (or `*vulkan*.json`) metadata and for
`libvulkan_*.so`. On import every ICD file is handed to the Vulkan loader:

```
VK_ICD_FILENAMES=/drivers/<relative path of the icd json>[:...]
ADRENOTOOLS_DRIVER_PATH=/drivers
ADRENOTOOLS_HOOKS_PATH=/drivers
GALLIUM_DRIVER=freedreno
```

If a pack contains a `meta.json` (`name`, `driverVersion`, `vendor`) or a
`pack.json` with an `env` object, that metadata is shown in the launcher and the
`env` entries are merged into the environment (user overrides still win).

> Turnip only works on Adreno (Qualcomm) GPUs. On Mali the practical option is
> Zink (OpenGL on top of the device Vulkan driver) or SwiftShader (very slow).
> If no driver pack is selected, the device Vulkan driver is used
> (`graphics driver = system`).

### 2.4 D3D translation layer (`d3d`)

DXVK (D3D9/10/11), VKD3D-Proton (D3D12) or D8VK (D3D8). The pack just needs the
DLLs:

```
pcgame/packs/d3d/dxvk-2.4/x64/d3d11.dll
pcgame/packs/d3d/dxvk-2.4/x64/dxgi.dll
pcgame/packs/d3d/dxvk-2.4/x86/d3d9.dll
```

On the next launch the DLLs are copied into the Wine prefix:
`x64/*` → `drive_c/windows/system32`, `x86/*`/`i386/*` →
`drive_c/windows/syswow64`. A marker file (`<prefix>/.newcatroid_d3d`) records
which pack is installed, so switching packs re-copies instead of mixing files.
`WINEDLLOVERRIDES` is set to `mscoree,mshtml=;d3d9,d3d10core,d3d11,dxgi,d3d12,d3d12core=n,b`
while a D3D pack is selected.

---

## 4. Block reference

New subcategory **Device → PC games (Wine + Box64)**:

| Block | What it does |
| --- | --- |
| `Set PC game folder to [path]` | Absolute path of the game, e.g. `/sdcard/Games/GTAV`. Auto-detects the first `.exe`. |
| `Set graphics driver to [name]` | Pack id / display name; `system` = device driver, `none` = disable. |
| `Set D3D layer (DXVK/VKD3D) to [name]` | Pack id / display name, `none` = Wine's builtin D3D. |
| `Set Wine/Box64 runtime to [name]` | Pack id; `auto` = first imported runtime. |
| `Set container rootfs to [path]` | Overrides the rootfs pack with a directory. |
| `Set PC environment variable [name] to [value]` | Any variable, applied last (wins over everything). |
| `Clear all PC environment variables` | Removes the overrides. |
| `Set PC game resolution to [w] x [h]` | Xvfb screen size, Wine virtual desktop size, VNC framebuffer size. |
| `Import PC pack (zip) of type [type]` | Opens the file picker, imports `driver`, `d3d`, `runtime`, `rootfs`; empty = detect. |
| `Launch PC game [exe] with arguments [args]` | Starts the container, shows the framebuffer in the stage. |
| `Launch PC game [exe] arguments [args] with touch profile [name]` | Same, plus the touch overlay. |
| `Stop the running PC game` | Kills the container process, hides the surface and the overlay. |
| `Bind the PC game log to [variable]` | Container output (Wine/Box64/DXVK errors) in a variable. |
| `Check the PC environment and store the report in [variable]` | Component check: rootfs, runtime, proot, driver, D3D, game folder, prefix, imported packs, exec mode. |
| `Open the PC game launcher` | The ready-made launcher dialog (see 5). |
| `Choose the PC game folder (browse files)` | Opens the built-in file browser and stores the folder plus the first `.exe` it finds. |
| `Choose a graphics driver zip (Turnip / Adreno) and install it` | File browser → import as a driver pack → select it. |
| `Choose a DXVK / VKD3D zip and install it` | File browser → import as a D3D layer → select it. |
| `Choose a Wine + Box64 archive and install it` | File browser → import as a runtime → select it. |
| `Choose a container rootfs archive and install it` | File browser → import as a rootfs → select it. |
| `Download PC components from the internet (drivers, DXVK, runtime, rootfs)` | Online catalogue: lists the release archives of the configured GitHub repositories, tap one to download + install + select. |
| `Download a PC component from the web address [url] as [type]` | Same, for one direct link; type may be empty (detected). |
| `Set [variable] to device memory [total/available/used/percent/app/low]` | Stores one RAM value of the phone in a variable (MB). |
| `Set [variable] to YES if the device has at least [MB] of RAM, checked as [total/free/both]` | Writes `YES` or `NO` into a variable - the "can this phone run it" check. |
| `Open the PC environment variable editor` | Editable list of variables plus one-tap tuning presets. |
| `Grant NewCatroid access to the game folder` | Opens the system "All files access" screen. |
| `Add touch control [label] keys [keys] at x% y% size%` | Adds/replaces one touch control. |
| `PC touch layout editor [enabled]` | Drag-to-move editing of the overlay; saved into the project. |

Settings are stored per project in `<project>/files/pcgame/config.json`, touch
layouts in `<project>/files/pcgame/touch_<profile>.json`, so a launcher project
keeps its configuration and can be exported/built as an APK.

### Choosing the game folder (and why it must be a real path)

A game folder lives outside the app sandbox (`/sdcard/Games/GTAV`,
`/storage/emulated/0/...`). Android 11+ only lets an app read it with the special
**"All files access"** permission - without it `File.listFiles()` returns `null`
and the layer reports *"game folder not found"* even for a correct path.

So the flow is:

1. `Grant NewCatroid access to the game folder` (or the first time you press
   *Browse folder…* / *Choose the PC game folder*), allow *All files access* in
   the system screen, come back.
2. `Choose the PC game folder (browse files)` → tap through the folders →
   *Use this folder*. The first `.exe` inside is detected automatically.
3. Everything is saved in the project config, so the next start remembers it.

The Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) is deliberately **not**
used for the game folder: it hands out a `content://` URI, and neither proot nor
Wine can mount or start that. Pack archives *may* come through SAF, because they
are copied into the app cache before extraction - the "import pack" block works
with both pickers.

### Minimal launcher script

```
when scene starts
    check the PC environment and store the report in "pc_report"
    grant NewCatroid access to the game folder          # only needed once
    choose the PC game folder (browse files)            # user taps the folder
    choose a graphics driver zip and install it         # user taps the Turnip zip
    choose a DXVK / VKD3D zip and install it            # user taps the dxvk tar.gz
    choose a Wine + Box64 archive and install it        # user taps the runtime
    set PC game resolution to 1280 x 720
    open the PC environment variable editor             # optional fine tuning
    launch PC game "GTA5.exe" arguments "-windowed" with touch profile "gamepad"
```

Everything the player picks in those dialogs ends up in the project config, so a
launcher only has to ask once. The "set … to [formula]" blocks stay available for
projects that hard-code a setup instead.

Optionally add a sprite with `when tapped → open the PC game launcher` so the
player can change the driver without editing the project.

---

## 5. The launcher dialog

`Open the PC game launcher` shows a dialog with:

* game folder (`Browse folder…` maps an SAF tree to `/storage/emulated/0/...`
  when the volume is `primary`),
* executable (`Find executables` lists the `.exe` files),
* graphics driver / D3D layer / runtime spinners (from the imported packs),
* resolution and DPI,
* *Show the game inside the stage*, *Enable touch controls*,
  *Edit touch layout (drag the buttons)*, *DXVK performance HUD*,
* `Play`, `Stop`, `Import pack…`, `Get packs online…` (the catalogue from
  section 2.0, with the category tabs), `Environment…`, `Self test`, `Save settings`,
* a live log tail (last 20 lines).

---

## 5.5 RAM: does this phone even have enough?

Wine + Box64 + DXVK need a lot of memory, and the failure mode without a check
is ugly: the game loads for a minute and then the Android low-memory killer
removes NewCatroid. So there are two ways to ask the question.

### Sensors (usable anywhere a formula is)

In the formula editor, *Device* → *Sensors*:

| Sensor | Value |
| --- | --- |
| `device RAM total (MB)` | physical RAM of the device, e.g. `8192` |
| `device RAM free (MB)` | RAM that can be handed out right now |
| `device RAM used (%)` | 0-100, for a HUD |
| `app RAM limit (MB)` | how far the Java heap of NewCatroid may grow |
| `device low on RAM` | 1 when Android itself reports low memory, else 0 |

That is enough for a hand-made check, for example

```
if  device RAM total (MB)  >=  6000  then
    launch PC game "GTA5.exe" with arguments ""
else
    say "This phone needs 6 GB of RAM for this game"
end
```

### The two blocks

* `Set [variable] to device memory [total]` - the same numbers as the sensors, but
  written into a variable so it can be shown on screen, sent to a log or
  compared later. Accepted values: `total`, `available`, `used`, `percent`,
  `app`, `low`.
* `Set [variable] to YES if the device has at least [8000] MB of RAM, checked as
  [both]` - writes the text `YES` or `NO`. The last field picks the comparison:

  | Mode | Meaning |
  | --- | --- |
  | `total` | only the hardware: "this phone has 8 GB, so it qualifies" |
  | `free` | only what is free right now: "it has 8 GB but 3 GB are in use" |
  | `both` | both of the above (default): the safe answer |

  `NO` is also written when Android reports a low-memory state, because the
  killer would end the game anyway.

### A launcher that checks first

```
when project starts
  set [ram_ok] to YES if the device has at least 6000 MB of RAM, checked as both
  if [ram_ok] = "YES" then
    open the PC game launcher
  else
    set [ram_total] to device memory total
    say join "Not enough RAM: " join (join [ram_total] " MB of ") "6000 MB needed"
  end
```

The numbers behind the answer are written to the PC log as well
(`RAM check: needs 6000 MB (both) -> NO; 5.8 GB total, 2.1 GB free, 63% used,
app limit 512.0 MB`), so a failed check can be diagnosed without guessing.

**What counts as "free"**: Android's `availMem`, which already counts reclaimable
page cache as available. It is not the same as "unused" - a phone that shows 55%
used can still be able to hand out 4 GB to a game. That is exactly why the third
field exists: `total` never fails because another app is running, `free` is the
realistic answer at launch time.

A note on expectations (same numbers as section 8): a 64-bit DX11 game through
Box64 needs about 6-8 GB of RAM *and* an Adreno 7xx-class GPU to reach a menu.
The RAM check answers "is it worth trying", not "will it run at 60 fps".

## 6. Touch controls

Positions/sizes are normalised (0..1 of the virtual screen), the same scheme the
WebView touch controls of the local server use:

```json
{
  "name": "gamepad",
  "version": 1,
  "opacity": 0.55,
  "controls": [
    { "id": "move", "kind": "joystick", "x": 0.16, "y": 0.76, "size": 0.32,
      "label": "WASD", "type": "key", "code": "w,a,s,d" },
    { "id": "look", "kind": "look", "x": 0.70, "y": 0.42, "size": 0.56,
      "label": "LOOK", "type": "mouse", "code": "", "button": 0 },
    { "id": "jump", "kind": "button", "x": 0.86, "y": 0.46, "size": 0.17,
      "label": "SPACE", "type": "key", "code": "space" }
  ]
}
```

* `kind`: `joystick` (four keys: up,left,down,right in `code`),
  `look` (drag = mouse look, tap = mouse click), `button`.
* `type`: `key` (comma-free, `+` combines keys: `ctrl+shift`) or `mouse`
  (`button`: 0 left, 1 middle, 2 right).
* Built-in presets: `gamepad`, `fps`, `platformer`, `empty`.

Presets are used when no JSON exists yet; `Add touch control` writes the JSON
into the project, the editor saves drag positions automatically.

The overlay only uses key names the stage already understands
(`esc`, `enter`, `tab`, `space`, `shift`, `ctrl`, `alt`, `up`/`down`/`left`/`right`,
`f1`…`f12`, single characters).

---

## 7. Environment variables and hooks

The generated script is written to
`<app files>/pcgame/scripts/launch.sh` and is human readable - it contains every
exported variable, the X/VNC startup, the prefix/D3D installation and the final
`exec box64 wine ...` line. Use the "bind PC game log" block or the launcher log
to see the output.

Set by NewCatroid (all can be overridden by the `Set PC environment variable`
block, which is applied last):

| Variable | Value |
| --- | --- |
| `WINEPREFIX`, `WINEARCH`, `WINEDEBUG`, `WINEESYNC`, `WINEFSYNC`, `WINE_LARGE_ADDRESS_AWARE` | Wine session |
| `WINEDLLOVERRIDES` | native d3d/dxgi when a D3D pack is selected |
| `BOX64_*` | `NOBANNER`, `DYNAREC_BIGBLOCK=3`, `STRONGMEM=2`, `FASTNAN`, `SAFEFLAGS=2`, `X87DOUBLE=0`, `FASTROUND`, `MMAP32`, `LD_LIBRARY_PATH` |
| `VK_ICD_FILENAMES`, `VK_DRIVER_FILES`, `ADRENOTOOLS_*`, `GALLIUM_DRIVER`, `TU_DEBUG`, `MESA_NO_ERROR` | graphics driver |
| `DXVK_STATE_CACHE`, `DXVK_LOG_LEVEL`, `DXVK_HUD`, `VKD3D_*` | D3D layer |
| `NC_*` | container hints (`NC_XSERVER_CMD`, `NC_VNC_CMD`, `NC_WINE_CMD`, `NC_BOX64_CMD`, `NC_INSTALL_D3D`, `NC_D3D_PACK`, `NC_VNC_PORT`, …) |

### The environment editor (no blocks needed)

`Open the PC environment variable editor` shows every override as a
name/value row: add a row, edit a value, delete a row, press *Save*. The values
are written to the project config and exported by the generated script, winning
over the defaults of the layer.

*Presets…* inserts the tuning sets that the Wine/Box64/DXVK projects document:

| Preset | Variables |
| --- | --- |
| DXVK HUD (fps + frametimes) | `DXVK_HUD=fps,frametimes,gpuload,devinfo` |
| Frame limit 60 fps | `DXVK_FRAME_RATE=60` |
| Box64 - performance | `BOX64_DYNAREC_BIGBLOCK=3`, `STRONGMEM=1`, `SAFEFLAGS=1`, `FASTROUND=1` |
| Box64 - stability | `BOX64_DYNAREC_BIGBLOCK=0`, `STRONGMEM=2`, `SAFEFLAGS=2`, `FASTROUND=0` |
| Old games (2003 GL extensions) | `MESA_EXTENSION_MAX_YEAR=2003` |
| Zink (OpenGL over Vulkan) | `GALLIUM_DRIVER=zink`, `ZINK_DESCRIPTORS=lazy`, `MESA_GLTHREAD=true` |
| Wine debug off / audio | `WINEDEBUG=-all`, `PULSE_LATENCY_MSEC=60` |

Optional hook: put `hook_pre.sh` into `<project>/files/pcgame/`. It is copied
into the container next to the launch script and sourced before the game starts -
handy to fix a runtime without rebuilding anything.

---

## 8. Troubleshooting

Use the **self test** block first; it prints one line per component.

| Symptom | Cause / fix |
| --- | --- |
| `Cannot execute proot` | The runtime has no `proot` in `bin/`. The layer tries `linker64 <proot>`, then direct exec, then `sh -c 'exec "$@"'`; the used mode is logged. If the device runs Android 10+ and refuses to execute the file at all, import a rootfs and let a self contained runtime do the job in host mode. |
| `Cannot start the runtime` in host mode | The runtime pack has no `bin/box64`. Host mode needs a pack with `box64`, `wine`, `Xvfb` and `x11vnc` inside. |
| `Game folder not found` | Use an absolute path and grant "all files access" to NewCatroid (`MANAGE_EXTERNAL_STORAGE`). |
| Black stage, log says x11vnc missing | The rootfs has no `x11vnc`; install it in the rootfs or point `NC_VNC_CMD` to another VNC server (`Set PC environment variable`). |
| Black stage with frames in the log | Wrong GPU driver for the device (Turnip on a non-Adreno GPU). Try `system`, Zink or SwiftShader. |
| `wine: module not found` / `d3d11` errors | D3D pack not installed yet (first launch installs it) or 32/64-bit mismatch: a 64-bit game needs `x64/` DLLs. |
| Game starts and immediately exits | Check the log; often a missing dependency (`vcrun`, `.NET`, `d3dx9`) that must be installed in the prefix, or an anti-cheat that cannot work in a container. |
| Very slow | Box64 + DXVK on a mobile SoC is heavy: lower the resolution, disable the virtual desktop, use `DXVK_HUD=fps` to measure, consider `BOX64_DYNAREC_BIGBLOCK=4`. |

### Reality check for GTA V specifically

GTA V is a DirectX 11, ~100 GB game with a Rockstar launcher and anti-cheat
parts. It needs a 64-bit Wine prefix, a working D3D11 → Vulkan path (DXVK), and
a lot of RAM/CPU. On a high-end Snapdragon (Adreno 7xx, 8-12 GB RAM) it is at the
very edge of what Box64 + Wine can do; on mid-range hardware it will not be
playable. Also note that Rockstar's services/anti-cheat are not supported in a
container - single player offline is the only realistic target. NewCatroid does
not and cannot promise playable frame rates; what it gives you is the launcher,
the driver/DXVK/environment plumbing, the display bridge, the touch controls and the
logs to debug it.

---

## 9. What is not implemented (yet)

Honest list, so nobody expects more than there is. Everything below exists in
Winlator/GameHub and is **not** in this layer:

* **Physical controllers (XInput / DirectInput).** The bundled libGDX build has no
  `controllers` extension, so a paired gamepad is not remapped onto Wine's XInput.
  Use the touch overlay, or the existing `key event` blocks for a mapping.
* **Drive letters.** Only the game folder, the Wine prefix, the runtime, the
  driver and the D3D layer are mounted (`/game`, `/prefix`, `/runtime`,
  `/drivers`, `/d3d`). There is no `D:` that points at `Downloads` and no way to
  add a drive from a block; a Wine installer that insists on a CD-ROM drive will
  not be happy.
* **Running an installer from the UI.** You can launch any `.exe`
  (`Launch PC game "setup.exe"`), but there is no dedicated "install into the
  prefix" flow with a visible desktop afterwards.
* **Per-container Wine versions.** One runtime pack is selected at a time; Wine
  versions are not per project (each project has its own prefix, though).
  The online catalogue can only list what a repository publishes as a release
  archive, and ready-made Android Wine builds are shipped inside APKs.
* **VirGL / Vortek presets.** `GALLIUM_DRIVER=zink` (plus the Zink preset) is the
  non-Adreno path; there is no VirGL or Vortek toggle.
* **Screen orientation lock, FPS overlay toggle outside DXVK, audio routing UI.**
  Mostly covered by environment variables (`DXVK_HUD`, `PULSE_LATENCY_MSEC`).
* **Anti-cheat, Rockstar launcher, online play.** Not possible through
  Wine/Box64/proot at all.

What *is* there: folder/pack pickers (with the "All files access" flow), the
online catalogue that downloads Turnip/DXVK/Box64/rootfs straight from GitHub, the
environment editor with presets, container and host run modes, display + input
over the existing VNC bridge, touch layout with editor, logging and the
environment self test.

## 10. Кратко по-русски

Добавлен слой совместимости с ПК-играми (как в Winlator/GameHub/Mobox), но
полностью на блоках NewCatroid:

* **Память (RAM)**: датчики `всего_RAM_(МБ)`, `свободно_RAM_(МБ)`,
  `занято_RAM_(%)`, `лимит_RAM_приложения_(МБ)`, `мало_RAM` плюс два блока —
  записать значение памяти в переменную и получить `YES`/`NO` по требованию
  (сравнение по `total`, `free` или `both`). Удобно, чтобы лаунчер не запускал
  игру, которую телефон не потянет.
* **Интернет-каталог**: блок «Скачать компоненты PC из интернета» показывает
  список релизов прямо с GitHub (Turnip от K11MCH1/AdrenoToolsDrivers, DXVK,
  VKD3D, D8VK, Box64, rootfs). Нажал — скачалось, распаковалось и выбралось.
  Можно добавить свой репозиторий (`owner/repo`) или скачать по прямой ссылке.
* **Пакеты** (zip) импортируются пользователем: `rootfs` (Linux-окружение с
  `Xvfb` и `x11vnc`), `runtime` (`bin/box64` + `bin/wine` для aarch64),
  графический драйвер (Turnip/AdrenoTools, Zink, SwiftShader) и слой D3D
  (DXVK/VKD3D/D8VK). В репозиторий и в APK ничего стороннего не входит.
* **Блоки**: папка игры, драйвер, слой D3D, рантайм, rootfs, переменные
  окружения, разрешение, импорт пакета, запуск игры, запуск с сенсорным
  управлением, остановка, лог в переменную, проверка окружения, встроенный
  лаунчер, добавление элементов сенсорного управления и редактор раскладки.
* **Экран и ввод** идут через тот же VNC-конвейер, что и QEMU-блоки: в
  контейнере поднимается `Xvfb` + `x11vnc`, кадр рисуется в сцене, а касания
  превращаются в настоящие X11 события (клавиши и мышь).
* **Горячие клавиши/отладка**: генерируемый скрипт
  `pcgame/scripts/launch.sh` читаемый, есть `hook_pre.sh` в файлах проекта и
  блок «проверить окружение», который показывает, чего не хватает.
* **Производительность**: Box64 + Wine + DXVK на телефоне — это тяжело. GTA V
  реалистична только на топовых Snapdragon (Adreno 7xx, 8+ ГБ ОЗУ) и только
  офлайн-одиночная игра; античит и онлайн не поддерживаются. Никаких гарантий
  по FPS нет — слой даёт лаунчер, драйверы, экран, управление и логи.
