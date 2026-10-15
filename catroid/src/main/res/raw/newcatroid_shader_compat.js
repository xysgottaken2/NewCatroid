/*
 * Opt-in correction for the reviewed PlayGTA5 worker's scalar alpha-test variant.
 * ?newcatroid_alpha_fix=1 is independent of diagnostics and sync pipelines.
 * HttpEngine permits the renderer hook only for the reviewed worker SHA-256.
 */
(function () {
    'use strict';
    var flag = 'newcatroid_alpha_fix';
    if (new URLSearchParams(self.location.search).get(flag) !== '1') return;

    // The appended script shares the original worker's lexical environment.
    // Both normal and remapped variants enter pixelModule. A scalar target has
    // no alpha component: do not synthesize an invalid scalar .w access.
    if (/\/wgpu_worker\.js$/.test(self.location.pathname)) {
        var originalPixelModule = pixelModule;
        pixelModule = function (program, alphaToCoverage, remap) {
            var hasAlpha = /\bvar\s*<\s*private\s*>\s+o0\s*:\s*(?:vec4\s*<\s*f32\s*>|vec4f)\s*;/.test(program.text);
            return originalPixelModule(program, alphaToCoverage && hasAlpha, remap);
        };
        console.info('[NewCatroid] scalar alpha-test correction applied (reviewed worker)');
        return;
    }

    // Propagate just this option page -> loader -> GPU worker. Do not wrap
    // messages, transfer lists, input, I/O workers, or the Emscripten pthreads.
    if (typeof self.Worker !== 'function') return;
    var targetName = typeof document === 'undefined' ? 'wgpu_worker.js' : 'loader.js';
    self.Worker = new Proxy(self.Worker, {
        construct: function (target, args, newTarget) {
            var forwarded = args.slice();
            var url = new URL(String(args[0]), self.location.href);
            if (url.origin === self.location.origin && url.pathname.endsWith('/' + targetName)) {
                url.searchParams.set(flag, '1');
                forwarded[0] = url.href;
            }
            return Reflect.construct(target, forwarded, newTarget);
        }
    });
})();
