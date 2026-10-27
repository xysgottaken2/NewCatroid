# Building the packs for the NewCatroid PC layer

`docs/PC_GAMES.md` explains the blocks, this file explains how to produce the
four archives they need. Nothing in this repository ships those binaries - you
import them yourself, which is also why the feature can ship in a fork under the
AGPL without vendoring GPL code.

Everything here is a **recipe**, not a guarantee: driver availability depends on
your GPU, and the Wine/Box64 combination depends on the runtime you pick.

A pack is just an archive; the importer accepts `.zip`, `.tar`, `.tar.gz`,
`.tar.xz` and `.tzst` and detects the format from the magic bytes. Inside the
archive there may be exactly one top level folder - it is flattened on import.
`pack.json`, `meta.json` and `driver.json` are read when present:

```json
{
  "name": "Turnip 25.1 (Adreno 740)",
  "version": "25.1",
  "vendor": "Mesa",
  "notes": "built with TU_DEBUG=noconform"
}
```

---

## 1. Graphics driver pack (`driver`)

The layer follows the **AdrenoTools** layout, because that is how Turnip driver
zips are distributed for Android:

```
meta.json                     <- optional but recommended
lib/libvulkan_freedreno.so    <- the actual Vulkan driver
lib/freedreno_icd.aarch64.json<- ICD manifest (the file VK_ICD_FILENAMES points at)
```

What the importer does with it:

* every `*icd*.json` / `*vulkan*.json` file becomes an entry of `VK_ICD_FILENAMES`
  and `VK_DRIVER_FILES`,
* the pack folder is exported as `ADRENOTOOLS_DRIVER_PATH`,
  `ADRENOTOOLS_HOOKS_PATH`, `LIBGL_DRIVERS_PATH` and appended to `GALLIUM_DRIVER=freedreno`,
* `meta.json` values (`name`, `driverVersion`, `vendor`) are shown in the
  launcher and in the self test,
* an `env` object inside `pack.json` is merged into the environment.

Checklist before you import: the archive must contain the `.so` **and** the ICD
`.json`; a pack with only the `.so` is imported but reported as "no ICD file" by
the self test, because Android's Vulkan loader cannot see a driver without ICD
metadata.

Notes:

* Turnip is the Mesa `freedreno` Vulkan driver and only works on **Qualcomm
  Adreno** GPUs. It is the fastest option for GTA V class games on a Snapdragon.
* On Mali/Immortalis the practical path is **Zink** (OpenGL on top of the device
  Vulkan driver, needs the Mesa build inside the rootfs) or SwiftShader (works
  everywhere, very slow - good for a smoke test).
* If you do not import any driver pack, leave the block at `system` and Wine will
  use the Vulkan driver that the device ships. DXVK still works, just slower.

## 2. D3D translation layer pack (`d3d`)

DXVK turns Direct3D 9/10/11 into Vulkan. Official builds are on GitHub
(`doitsujin/dxvk`, e.g. release `v3.1.1` with asset `dxvk-3.1.1.tar.gz`):

```sh
tar xf dxvk-3.1.1.tar.gz          # -> dxvk-3.1.1/x64/*.dll and x32/*.dll
cd dxvk-3.1.1 && zip -r ../dxvk-3.1.1.zip .   # keep x64/ and x32/ as folders
```

Import that zip with type `d3d`. On the next launch the layer copies

* `x64/*.dll` → `drive_c/windows/system32`
* `x32/*.dll` → `drive_c/windows/syswow64`

and sets `WINEDLLOVERRIDES=…;d3d9,d3d10core,d3d11,dxgi,d3d12,d3d12core=n,b` so Wine
uses the native DLLs instead of its own. A marker file remembers which pack is
installed, so switching between two DXVK versions does not mix files.

* GTA V is Direct3D 11 → **DXVK is the one you need**.
* `VKD3D-Proton` is only needed for Direct3D 12 games.
* `D8VK` is for very old Direct3D 8 titles.
* `DXVK_HUD=fps,frametimes,gpuload,devinfo` (launcher checkbox or the
  environment block) is the quickest way to see whether frames are produced at
  all - if the overlay never appears, the game never reached Direct3D.

## 3. Wine + Box64 runtime pack (`runtime`)

Box64 translates x86_64 instructions to ARM64, Wine implements the Windows API.
Both must be built for **aarch64 Android/Linux** (not for glibc x86_64!).

```
bin/box64            <- ptitSeb/box64, built for aarch64
bin/wine             <- the wine loader of an arm64 Wine build
lib/                 <- the Wine libraries (libwine.so*, wine/…)
share/wine/          <- Wine data files (fonts, nls, …)
```

The folder is bind-mounted at `/runtime`, `/runtime/bin` is prepended to `PATH`
and `/runtime/lib` to `LD_LIBRARY_PATH`, so `box64 wine` finds everything. The
script always calls them as

```
$NC_BOX64_CMD "$NC_WINE_CMD" <exe> <args>
```

which means you can replace both commands from a block
(`NC_BOX64_CMD=/system/bin/linker64 /path/box64`, `NC_WINE_CMD=wine64`, …).

In **host mode** (no rootfs imported) the box64 command automatically becomes
`/system/bin/linker64 <runtime>/bin/box64`, because Android 10+ refuses to
`execve` files inside the app data directory.

## 4. Container rootfs (`rootfs`)

A proot-compatible Linux user space: `bin/sh`, `usr/`, `etc/`, `lib/`, plus the
pieces the layer starts itself:

* an X server that can run without a GPU screen: `Xvfb` (package `xvfb`),
* a VNC bridge that accepts input: `x11vnc` (package `x11vnc`),
* optionally `pulseaudio`, `wine` dependencies, `xdotool`, `glxinfo`.

Recipe on a PC (needs root for the chroot-less unpack, or use `fakeroot`):

```sh
mkdir rootfs && cd rootfs
sudo debootstrap --arch=arm64 --variant=minbase bookworm .   # or start from any arm64 rootfs
sudo chroot . apt-get install -y xvfb x11vnc
sudo tar -c . | zstd -19 -o ../newcatroid-rootfs.tzst
```

Import the `.tzst` with type `rootfs`. `Xvfb` and `x11vnc` are started by the
generated script (`NC_XSERVER_CMD`, `NC_VNC_CMD`) and the stage shows their
framebuffer through the same VNC client the QEMU blocks use - so touches and key
blocks reach the game as real X11 events.

If your rootfs already contains box64/wine (a Winlator or Mobox rootfs does),
you can skip the runtime pack and just point `NC_BOX64_CMD` / `NC_WINE_CMD` at
the binaries inside the rootfs.

## 5. Smoke test order

1. Import the rootfs, run *check the PC environment* - it must say
   `[ok] rootfs: …` and `[ok] X server: Xvfb`, `[ok] VNC bridge: x11vnc`.
2. Launch with a trivial Windows exe (`notepad.exe` from Wine, or
   `winecfg.exe`). If the stage shows a window and the touch layout taps work,
   the display and input pipeline are fine.
3. Import the D3D pack, run `dxvk_hud` and a Direct3D sample (`dxdiag.exe`,
   or a small D3D11 demo). The HUD proves that DXVK reached Vulkan.
4. Only then point the game folder at GTA V and raise the resolution step by
   step; start at `800 x 600` and `DXVK_HUD=fps`.

## 6. What to expect from GTA V

| Device class | Realistic outcome |
| --- | --- |
| Snapdragon 8 Gen 2/3 + Adreno 740/750, 12 GB RAM | single player may boot and run at a low, unstable frame rate with DXVK + Turnip at 800x600 - a tech demo, not a playable port |
| Snapdragon 7-8 Gen 1, 8 GB RAM | boots to the menu at best; the open world will stutter or fail to load textures |
| Anything below / Mali GPUs | not playable; expect crashes, black screens or immediate exits |

Additional reality checks:

* GTA V (legacy, ~100 GB) is Direct3D 11 and needs a 64-bit prefix; the
  "Enhanced" edition pushes the requirements further (D3D12 → VKD3D).
* Rockstar's launcher and anti-cheat components do not work inside Wine/proot.
  Offline single player is the only realistic target.
* Textures have to fit into RAM: 12 GB is the practical minimum for low settings.
* Use a **game folder on the internal storage** (`/sdcard/Games/GTAV`); exFAT SD
  cards make the first load take minutes and break some file locking.

None of this is hidden in the app: the log block shows exactly which step failed,
and the generated `launch.sh` contains the full command line and environment.
