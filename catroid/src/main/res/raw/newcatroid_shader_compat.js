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
        var alphaCompatibilityStats = {
            enabled: true,
            hookInstalled: true,
            alphaCoverageVariantsSeen: 0,
            nonVec4AlphaTestsBypassed: 0,
            vec4AlphaTestsPreserved: 0,
            firstNonVec4OutputType: '',
            lastObservedAt: null
        };
        self.__newcatroidAlphaCompatibility = alphaCompatibilityStats;
        var originalPixelModule = pixelModule;
        pixelModule = function (program, alphaToCoverage, remap) {
            var useAlphaToCoverage = !!alphaToCoverage;
            if (useAlphaToCoverage) {
                var text = program && typeof program.text === 'string' ? program.text : '';
                var output = /\bvar\s*<\s*private\s*>\s+o0\s*:\s*([^;]+)\s*;/.exec(text);
                var outputType = output ? output[1].replace(/\s+/g, '') : 'unknown';
                var hasAlpha = /^(?:vec4<f32>|vec4f)$/.test(outputType);
                alphaCompatibilityStats.alphaCoverageVariantsSeen++;
                alphaCompatibilityStats.lastObservedAt = Date.now();
                if (hasAlpha) {
                    alphaCompatibilityStats.vec4AlphaTestsPreserved++;
                } else {
                    alphaCompatibilityStats.nonVec4AlphaTestsBypassed++;
                    if (!alphaCompatibilityStats.firstNonVec4OutputType) {
                        alphaCompatibilityStats.firstNonVec4OutputType = outputType.slice(0, 48);
                    }
                }
                useAlphaToCoverage = hasAlpha;
            }
            return originalPixelModule(program, useAlphaToCoverage, remap);
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
