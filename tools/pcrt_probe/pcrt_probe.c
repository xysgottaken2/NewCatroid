/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * pcrt_probe - the PC runtime's native ARM64 smoke test.
 *
 * Purpose
 * -------
 * This is a *separate process*, not a JNI call. It exists to prove, on a real
 * device, that the whole out-of-process launch path works end to end:
 *
 *   Android app -> PcExecPlanner -> ProcessBuilder -> /system/bin/linker64
 *                -> dynamic linking -> this binary -> stdout -> exit status
 *
 * That path is exactly the path a future Box64 + Wine runtime has to use, so
 * verifying it is a prerequisite for any Windows compatibility work. It is
 * also the tripwire for the two Android-specific failure modes:
 *
 *   * Android 10+ W^X: exec() of app-data files is denied, so the binary has
 *     to be started through the system linker (or shipped in jniLibs).
 *   * Instruction-set self check: the binary prints the ELF machine it was
 *     actually built for, so a mis-packaged ABI shows up immediately.
 *
 * Output is a single line of `key=value` pairs followed by a newline, plus a
 * JSON blob when invoked with --json. Nothing is written to disk and no
 * privileged operation is attempted.
 *
 * Exit codes:
 *   0 - probe completed
 *   3 - the process was not started through an interpreter we expect
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/utsname.h>
#include <sys/auxv.h>

#if defined(__aarch64__)
#  define PCRT_ARCH "aarch64"
#elif defined(__arm__)
#  define PCRT_ARCH "arm"
#elif defined(__x86_64__)
#  define PCRT_ARCH "x86_64"
#elif defined(__i386__)
#  define PCRT_ARCH "i386"
#else
#  define PCRT_ARCH "unknown"
#endif

/* Hardware capabilities have to be read from the *device*, not from what the
 * compiler happened to target: the probe is built for the baseline ABI, so
 * __ARM_FEATURE_CRC32 and __ARM_FEATURE_DOTPROD are 0 even on a Snapdragon
 * 8 Gen 2 whose cores do support both. Reporting that as "unsupported" would be
 * a false negative, so AT_HWCAP is consulted on ARM and the compile time macros
 * are only the fallback for builds made without getauxval.
 *
 * Bit numbers are taken from the kernel UAPI headers
 * (arch/arm64/include/uapi/asm/hwcap.h, arch/arm/include/uapi/asm/hwcap.h). */
#define PCRT_HWCAP_ASIMD    (1UL << 1)   /* AArch64: Advanced SIMD (NEON) */
#define PCRT_HWCAP_CRC32    (1UL << 7)   /* AArch64: CRC32 instructions */
#define PCRT_HWCAP_ASIMDDP  (1UL << 20)  /* AArch64: dot product instructions */
#define PCRT_HWCAP_NEON_32  (1UL << 12)  /* AArch32: NEON */

static int hwcap_has(unsigned long mask) {
#if defined(__aarch64__) || defined(__arm__)
    const unsigned long hwcap = getauxval(AT_HWCAP);
    if (hwcap != 0) {
        return (hwcap & mask) ? 1 : 0;
    }
#else
    (void)mask; /* not an ARM build: the caller uses the compile time macros */
#endif
    return -1; /* unknown: caller falls back to the compile time macro */
}

/* Deterministic checksum: 146 full cycles of (0+3+6+2+5+1+4)=21 for i=0..1021,
 * then i=1022 (0) and i=1023 (3), so a correct binary prints 3069. It proves
 * the process ran the compiled code on this CPU, which is what makes the probe
 * a smoke test and not just a "did it start" check. */
#define PCRT_SELF_TEST_EXPECTED 3069L

static long self_test(void) {
    long total = 0;
    for (long i = 0; i < 1024; i++) {
        total += (i * 3) % 7;
    }
    return total;
}

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  define PCRT_NEON_FALLBACK 1
#else
#  define PCRT_NEON_FALLBACK 0
#endif
#if defined(__ARM_FEATURE_CRC32)
#  define PCRT_CRC32_FALLBACK 1
#else
#  define PCRT_CRC32_FALLBACK 0
#endif
#if defined(__ARM_FEATURE_DOTPROD)
#  define PCRT_DOTPROD_FALLBACK 1
#else
#  define PCRT_DOTPROD_FALLBACK 0
#endif

static void print_kv(const char *key, const char *value) {
    printf("%s=%s\n", key, value ? value : "");
}

static void print_kv_int(const char *key, long value) {
    printf("%s=%ld\n", key, value);
}

int main(int argc, char **argv) {
    int json = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--json") == 0) {
            json = 1;
        }
    }

    struct utsname uts;
    const char *sysname = "unknown";
    const char *release = "unknown";
    const char *machine = "unknown";
    if (uname(&uts) == 0) {
        sysname = uts.sysname;
        release = uts.release;
        machine = uts.machine;
    }

    const long self_test_result = self_test();
    const long self_test_ok = (self_test_result == PCRT_SELF_TEST_EXPECTED) ? 1 : 0;

    /* Prefer the kernel's view; fall back to what the binary was built for. */
    const int asimd = hwcap_has(PCRT_HWCAP_ASIMD);
    const int crc32 = hwcap_has(PCRT_HWCAP_CRC32);
    const int dotprod = hwcap_has(PCRT_HWCAP_ASIMDDP);
    const int neon32 = hwcap_has(PCRT_HWCAP_NEON_32);

    int neon = (asimd >= 0) ? asimd : (neon32 >= 0 ? neon32 : PCRT_NEON_FALLBACK);
    int has_crc32 = (crc32 >= 0) ? crc32 : PCRT_CRC32_FALLBACK;
    int has_dotprod = (dotprod >= 0) ? dotprod : PCRT_DOTPROD_FALLBACK;

    if (json) {
        printf("{\"arch\":\"%s\",\"machine\":\"%s\",\"sysname\":\"%s\",\"release\":\"%s\","
               "\"pointerSize\":%d,\"pid\":%ld,\"ppid\":%ld,\"neon\":%d,\"crc32\":%d,"
               "\"dotprod\":%d,\"selfTestResult\":%ld,\"selfTestOk\":%ld}\n",
               PCRT_ARCH, machine, sysname, release,
               (int)sizeof(void *), (long)getpid(), (long)getppid(),
               neon, has_crc32, has_dotprod, self_test_result, self_test_ok);
        fflush(stdout);
        /* Same contract as the text mode: a failed self test must be visible to
         * the caller as a non-zero exit status, never only in the output. */
        return self_test_ok ? 0 : 3;
    }

    print_kv("probe", "pcrt_probe");
    print_kv("arch", PCRT_ARCH);
    print_kv("uname_machine", machine);
    print_kv("uname_sysname", sysname);
    print_kv("uname_release", release);
    print_kv_int("pointer_size", (long)sizeof(void *));
    print_kv_int("pid", (long)getpid());
    print_kv_int("ppid", (long)getppid());
    print_kv_int("neon", neon);
    print_kv_int("crc32", has_crc32);
    print_kv_int("dotprod", has_dotprod);
    print_kv_int("self_test_expected", PCRT_SELF_TEST_EXPECTED);
    print_kv_int("self_test_result", self_test_result);
    print_kv_int("self_test_ok", self_test_ok);
    fflush(stdout);

    /* A non-zero exit for a failed self test keeps the failure visible to the
     * block layer instead of being buried in stdout. */
    return self_test_ok ? 0 : 3;
}
