# PC runtime

The PC runtime is the part of NewCatroid that is meant to run PC software
(Windows programs and games) on an Android ARM64 device. This document
describes what exists today, what the runtime expects from the components you
install, and how to verify each step on a device.

## What this runtime does today

It **diagnoses and prepares**, and it deliberately does not pretend otherwise:

* detects the device (ABI, 64-bit support, CPU cores, SoC, SDK level);
* probes Vulkan through `dlopen("libvulkan.so")` and reports the loader
  version, every device, the extensions and the feature flags a driver really
  exposes;
* validates whatever component files *you* place in the runtime directories
  (format, architecture, hash) before anything is started;
* creates a Wine prefix layout under the app's private storage;
* runs a small bundled AArch64 smoke test (`pcrt_probe`) through the same
  launch path a future CPU translator will need;
* answers every query as a block, so a project can branch on the result.

It does **not** download Box64, Wine, DXVK or VKD3D-Proton, and it does not
execute any Windows binary. Those components are large, separately licensed and
must be built or obtained by you; see *Installing components* below.

## Android constraints that shape everything

The app declares `targetSdk 33`, so Android 10+ SELinux rules apply: an
untrusted app **cannot `execve()` a file inside its own writable home
directory**. There are exactly two sanctioned ways to start a bundled
executable, and `PcExecPlanner` implements both:

| Mode | When | Mechanism |
| --- | --- | --- |
| `DIRECT` | the file lives in the extracted native library directory (`/data/app/.../lib/arm64`) | normal `execve()` of the file |
| `SYSTEM_LINKER` | any other location (app data, assets copied to app storage) | `execve("/system/bin/linker64", [binary, args...])` |

A *statically linked* binary outside the native library directory has no
interpreter to go through and is reported as `BLOCKED` with the reason; it is
never attempted.

`DIRECT` requires the native library directory to actually contain extracted
files (`android:extractNativeLibs="true"`, or `packaging { jniLibs
{ useLegacyPackaging = true } }` in AGP). This project does not currently set
that flag, and its native payload is loaded from the APK instead. If a future
step ships an executable in `jniLibs`, that flag has to be turned on first —
otherwise the file will not exist as a file and the runtime reports a missing
executable rather than silently failing.

## Component layout

`Initialize PC runtime` creates `${filesDir}/pcruntime/` with one directory per
component kind:

```
pcruntime/
  bin/           pcrt_probe (installed automatically from the APK assets)
  components/
    cpu-translator/   Box64 (AArch64 build)
    wine/             Wine (the Windows API implementation)
    userland/         glibc/musl userland + rootfs, if the Wine build needs one
    dxvk/             DXVK Direct3D 8/9/10/11 -> Vulkan DLLs
    vkd3d/            VKD3D-Proton Direct3D 12 -> Vulkan DLLs
    display/          X11 display server or VNC bridge, if used
  prefixes/      Wine prefixes created by 'Prepare Wine prefix'
```

The registry **validates** what it finds and reports the reasons when a file is
unusable:

* `cpu-translator` — must be a **dynamic AArch64 ELF** (a statically linked
  translator cannot be launched, and an x86-64 build is a common mistake);
* `dxvk` / `vkd3d` — must be **x86-64 PE DLLs** (`d3d11.dll`, `dxgi.dll`,
  `d3d9.dll`, `d3d8.dll`, `d3d12.dll`, `vkd3d-proton.cfg` is not a DLL);
* `wine` / `userland` / `display` — described, hashed and listed, with
  format/architecture problems reported.

Nothing is executed during validation. Files are read, hashed (SHA-256 prefix)
and inspected.

## Installing components

Everything below is a manual step. The runtime never downloads executables by
itself, and there is no "install" block.

### CPU translator: Box64

* Purpose: translate x86-64 Linux/Windows binaries to ARM64 at runtime.
* Source: <https://github.com/ptitSeb/box64> (latest release at the time of
  writing: `v0.4.4`). License: **MIT**.
* Android build (upstream `docs/COMPILE.md`): `cmake .. -D ARM64=1
  -DCMAKE_C_COMPILER=gcc -DBAD_SIGNAL=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo`,
  with a per-SoC preset for tuning. Upstream documents that Box64 is built on
  an ARM64 machine (Termux/Android or a native ARM64 host); `binfmt_misc` is
  not usable on Android.
* Put the resulting binary in `components/cpu-translator/`. The registry
  accepts it only if it is a dynamic AArch64 ELF.
* Useful environment variables when you later launch through it: `BOX64_PATH`,
  `BOX64_LD_LIBRARY_PATH`, `BOX64_LOG`.

### Windows API layer: Wine

* Purpose: implement the Windows API on top of POSIX.
* Source: <https://www.winehq.org> / <https://github.com/wine-mirror/wine>.
  License: **LGPL-2.1-or-later** (see `COPYING` in the Wine tree; some bundled
  parts have their own licenses).
* There is no single "Wine for Android" build. The realistic options are:
  1. **glibc Wine inside a bundled userland** — a Debian/Ubuntu-style rootfs
     with glibc plus a Wine build, placed in `components/userland/` and
     `components/wine/`. This is the approach the community uses with
     `proot`/`chroot` on Android.
  2. **A bionic-patched Wine** (for example the *hangover* patch set), which
     avoids the glibc rootfs but carries patches that must be rebased for every
     Wine release.
  Either way this is the hardest component in the stack, and the runtime does
  not hide that: without a usable `wine` component the state stays at
  `READY_WITHOUT_WINDOWS_LAYER`.
* After installing, run `wineboot` **inside the prefix** (through Box64 and
  Wine) before any program. `Prepare Wine prefix` creates the directory layout
  and always reminds you that `wineboot` is still outstanding; it never claims
  a prefix is usable when it has not been bootstrapped.

### Graphics: DXVK and VKD3D-Proton

* DXVK (<https://github.com/doitsujin/dxvk>, **zlib**) translates Direct3D
  8/9/10/11 to Vulkan. Put the x86-64 DLLs in `components/dxvk/`.
* VKD3D-Proton (<https://github.com/HansKristian-Work/vkd3d-proton>,
  **LGPL-2.1**) translates Direct3D 12 to Vulkan. Put the x86-64 DLLs in
  `components/vkd3d/`.
* Both are useless until Wine actually runs the program: `Select graphics
  backend` reports the *requirement* evaluation against the device's real
  Vulkan features, and refuses to call a backend ready when something is
  missing.

Requirement levels encoded in `GraphicsRequirementCatalog`:

| Backend | Requirements checked |
| --- | --- |
| DXVK (D3D8/9/10/11) | Vulkan 1.3 + `VK_KHR_maintenance5` + `graphicsPipelineLibrary` |
| VKD3D-Proton (D3D12) | Vulkan 1.3 + `VK_EXT_robustness2` + `VK_KHR_push_descriptor` + `descriptorIndexing` + `shaderDrawParameters` + `samplerMirrorClampToEdge` + ≥ 1,000,000 UpdateAfterBind sampled/storage image descriptors |

These are the DXVK 2.x / VKD3D-Proton-class requirements. **DXVK 3.x needs a
newer driver generation and Wine 10 or newer on top of them**, so a "ready"
result does not automatically mean the newest DXVK release will work. Check the
upstream release notes for the version you install.

On the target device class (Snapdragon 8 Gen 2 / Adreno 740) the proprietary
driver reports Vulkan 1.3. The open source **Mesa Turnip** driver (MIT) is the
usual choice for this work and gained A7xx support in Mesa 23.3; on A7xx, mesh
shaders and wave32 should stay disabled for Direct3D 12 workloads.

### Display

Wine needs an X11 display. Options are a Java X server inside the app (the
approach Winlator takes), an external Termux-X11 session, or the VNC client
module that already exists in this repository (`:vncclient`). Nothing is
implemented for the PC runtime yet; `components/display/` is prepared and
listed like the other components.

## Verifying on a device

1. `Initialize PC runtime` → reports ABI, SoC, SDK, Vulkan state and the
   Windows-layer state.
2. `Read PC runtime info` → query *Status*, *Vulkan*, *Vulkan extensions*,
   *Graphics readiness*, *Components*, *Prefixes*, *Log*.
3. `Run native runtime probe` → installs `pcrt_probe` from the APK assets and
   runs it through the system linker. Expect a JSON line with `arch`, `machine`,
   `neon`, `crc32`, `dotprod`, `selfTestResult`/`selfTestOk` and pointers to the
   launch mode that was used. A missing asset, a non-zero exit status or a
   blocked launch is reported as an error, never as a pass.
4. `Prepare Wine prefix` → `prefixes/<name>/drive_c`, `system32`, `syswow64`,
   `dosdevices` are created; the block states that `wineboot` still has to run.
5. `Select graphics backend` → the requirement table above is evaluated against
   the device. On a device without the Wine layer the result is "not ready"
   with the specific missing requirement.
6. `Inspect binary` → point it at any file (for example a Box64 binary) and read
   the format, architecture, interpreter/toolchain and the exact reason if the
   Android W^X rules would block it.

## Licences and notices

The runtime itself is part of NewCatroid (AGPL-3.0-family). The components you
install are **not** covered by that licence:

| Component | Licence (verified via the project's repository) |
| --- | --- |
| Box64 | MIT |
| Wine | LGPL-2.1-or-later (check `COPYING`) |
| DXVK | zlib |
| VKD3D-Proton | LGPL-2.1 |
| Mesa / Turnip | MIT |
| Termux packages | mixed (GPL/LGPL/Apache/MIT per package) |

If you redistribute an APK that contains any of these components, you must ship
their licence texts and honour the LGPL requirements (including the ability to
replace the library with a modified version). Keep the components outside the
APK while developing.

## What is still missing

* Box64 launching a real x86-64 binary on a physical device (no device test yet).
* Wine of any flavour actually starting (the largest unknown).
* A display path inside the app.
* DXVK/VKD3D injection into a Wine process.
* A real *Launch program* / *Stop program* / *Exit code* / *Get log* block set
  backed by verified execution — those blocks do not exist yet on purpose,
  because nothing exists to back them.
