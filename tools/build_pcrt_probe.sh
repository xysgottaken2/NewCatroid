#!/usr/bin/env bash
#
# Builds the pcrt_probe native smoke test for every Android ABI the app ships
# and drops the results into catroid/src/main/assets/pcruntime/ so that Gradle
# packages them.
#
# The Android NDK is required. The script never downloads anything.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk ./tools/build_pcrt_probe.sh
#   ANDROID_NDK_HOME=/path/to/ndk ./tools/build_pcrt_probe.sh arm64-v8a
#
# Why assets instead of jniLibs?
#   Gradle packages every file under jniLibs/<abi>/ as lib<name>.so and
#   Android's package manager extracts those with the executable bit already
#   set. That is the *other* sanctioned way to run a bundled binary. We ship
#   the probe through assets and start it with /system/bin/linker64 so that the
#   runtime exercises the same launch path a Box64/Wine binary would need, and
#   so that a missing probe asset degrades into a clear "component missing"
#   error instead of an unexplained crash.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SOURCE="${SCRIPT_DIR}/pcrt_probe/pcrt_probe.c"
OUTPUT_ROOT="${REPO_ROOT}/catroid/src/main/assets/pcruntime"

MIN_API="${PCRT_MIN_API:-26}"

declare -A ABI_TRIPLE=(
  [arm64-v8a]="aarch64-linux-android"
  [armeabi-v7a]="armv7a-linux-androideabi"
  [x86_64]="x86_64-linux-android"
)

if [[ -n "${1:-}" ]]; then
  ABIS=("$1")
  if [[ -z "${ABI_TRIPLE[$1]:-}" ]]; then
    echo "unsupported ABI: $1 (expected one of ${!ABI_TRIPLE[*]})" >&2
    exit 2
  fi
else
  ABIS=(arm64-v8a armeabi-v7a x86_64)
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for candidate in \
      "${ANDROID_NDK_ROOT:-}" \
      "${ANDROID_HOME:-}/ndk"/* \
      "${ANDROID_SDK_ROOT:-}/ndk"/* \
      /usr/local/lib/android/sdk/ndk/* ; do
    if [[ -n "${candidate}" && -d "${candidate}/toolchains/llvm/prebuilt" ]]; then
      ANDROID_NDK_HOME="${candidate}"
      break
    fi
  done
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2
  exit 3
fi

TOOLCHAIN_ROOT="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt"
HOST_TAG=""
for tag in linux-x86_64 linux-aarch64 darwin-x86_64 darwin-arm64; do
  if [[ -d "${TOOLCHAIN_ROOT}/${tag}" ]]; then
    HOST_TAG="${tag}"
    break
  fi
done

if [[ -z "${HOST_TAG}" ]]; then
  echo "No LLVM toolchain found under ${TOOLCHAIN_ROOT}" >&2
  exit 4
fi

BIN_DIR="${TOOLCHAIN_ROOT}/${HOST_TAG}/bin"
echo "NDK:       ${ANDROID_NDK_HOME}"
echo "Toolchain: ${BIN_DIR}"
echo "Source:    ${SOURCE}"
echo

for abi in "${ABIS[@]}"; do
  triple="${ABI_TRIPLE[$abi]}"
  compiler="${BIN_DIR}/${triple}${MIN_API}-clang"

  # The 32-bit ARM triple needs the armeabi-v7a-specific compiler name.
  if [[ ! -x "${compiler}" ]]; then
    echo "missing compiler for ${abi}: ${compiler}" >&2
    exit 5
  fi

  destination="${OUTPUT_ROOT}/${abi}"
  mkdir -p "${destination}"

  echo "-> building ${abi}"
  "${compiler}" \
    -O2 \
    -std=c11 \
    -Wall \
    -Wextra \
    -fPIE \
    -pie \
    -o "${destination}/pcrt_probe" \
    "${SOURCE}"

  chmod 755 "${destination}/pcrt_probe"
  # Record the ABI for the runtime so a mismatched asset is obvious on device.
  printf '%s\n' "${abi}" > "${destination}/ABI"
done

echo
echo "Built probes:"
find "${OUTPUT_ROOT}" -name pcrt_probe -exec ls -l {} \;
echo
echo "Remember: these files are build outputs. The CI workflow builds them on"
echo "every run; do not commit them (see .gitignore)."
