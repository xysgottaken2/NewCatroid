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
| Blocks (17) | `content/bricks/Pc*Brick.java`, `content/actions/Pc*.kt` |
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
| `Open the PC game launcher` | The ready-made launcher dialog (see 4). |
| `Add touch control [label] keys [keys] at x% y% size%` | Adds/replaces one touch control. |
| `PC touch layout editor [enabled]` | Drag-to-move editing of the overlay; saved into the project. |

Settings are stored per project in `<project>/files/pcgame/config.json`, touch
layouts in `<project>/files/pcgame/touch_<profile>.json`, so a launcher project
keeps its configuration and can be exported/built as an APK.

### Minimal launcher script

```
when scene starts
    check the PC environment and store the report in "pc_report"
    set PC game folder to "/sdcard/Games/GTAV"
    set graphics driver to "turnip"
    set D3D layer (DXVK/VKD3D) to "dxvk"
    set Wine/Box64 runtime to "auto"
    set PC game resolution to 1280 x 720
    launch PC game "GTA5.exe" arguments "-windowed" with touch profile "gamepad"
```

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
* `Play`, `Stop`, `Import pack…`, `Self test`, `Save settings`,
* a live log tail (last 20 lines).

---

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

## 9. Кратко по-русски

Добавлен слой совместимости с ПК-играми (как в Winlator/GameHub/Mobox), но
полностью на блоках NewCatroid:

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
