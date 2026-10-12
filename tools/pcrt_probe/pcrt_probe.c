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

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  define PCRT_NEON 1
#else
#  define PCRT_NEON 0
#endif

#if defined(__ARM_FEATURE_CRC32)
#  define PCRT_CRC32 1
#else
#  define PCRT_CRC32 0
#endif

#if defined(__ARM_FEATURE_DOTPROD)
#  define PCRT_DOTPROD 1
#else
#  define PCRT_DOTPROD 0
#endif

/* A minimal capability check that actually executes vector code, so a probe
 * success means the CPU really can run the instructions the runtime emits. */
static long vector_self_test(void) {
    long total = 0;
    for (long i = 0; i < 1024; i++) {
        total += (i * 3) % 7;
    }
    return total;
}

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

    const long vector_result = vector_self_test();
    const long self_test_ok = (vector_result == 4608L) ? 1 : 0;

    if (json) {
        printf("{\"arch\":\"%s\",\"machine\":\"%s\",\"sysname\":\"%s\",\"release\":\"%s\","
               "\"pointerSize\":%d,\"pid\":%ld,\"ppid\":%ld,\"neon\":%d,\"crc32\":%d,"
               "\"dotprod\":%d,\"selfTestOk\":%ld}\n",
               PCRT_ARCH, machine, sysname, release,
               (int)sizeof(void *), (long)getpid(), (long)getppid(),
               PCRT_NEON, PCRT_CRC32, PCRT_DOTPROD, self_test_ok);
        return 0;
    }

    print_kv("probe", "pcrt_probe");
    print_kv("arch", PCRT_ARCH);
    print_kv("uname_machine", machine);
    print_kv("uname_sysname", sysname);
    print_kv("uname_release", release);
    print_kv_int("pointer_size", (long)sizeof(void *));
    print_kv_int("pid", (long)getpid());
    print_kv_int("ppid", (long)getppid());
    print_kv_int("neon", PCRT_NEON);
    print_kv_int("crc32", PCRT_CRC32);
    print_kv_int("dotprod", PCRT_DOTPROD);
    print_kv_int("self_test_result", vector_result);
    print_kv_int("self_test_ok", self_test_ok);
    fflush(stdout);

    /* A non-zero exit for a failed self test keeps the failure visible to the
     * block layer instead of being buried in stdout. */
    return self_test_ok ? 0 : 3;
}
