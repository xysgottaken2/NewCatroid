/*
 * NewCatroid optional touch controls overlay for hosted browser games.
 *
 * Served by the local HTTP server at /__newcatroid_touch_controls.js and
 * injected into a hosted HTML page ONLY when the page URL asks for it
 * (?newcatroid_touch=1). This file is a separate, removable overlay: no
 * hosted game file, engine binary or generated script is modified.
 *
 * The overlay does NOT talk to the game engine directly. It dispatches
 * synthetic DOM keyboard/mouse events, which the page's EXISTING input
 * handlers forward to the engine through its own input pathway (the page's
 * key table and shared input memory). So movement, camera and buttons use
 * the game's real control system - nothing is invented or bypassed.
 *
 * Layout: left virtual joystick for movement (WASD), right-side drag area
 * for camera/look (mouse look), buttons for jump (Space), sprint (Shift),
 * crouch (Ctrl) and fire (left mouse button) - the standard mappings the
 * page's key table already defines. Multi-touch: every control tracks its
 * own touch identifier, so movement, camera and buttons work at once.
 *
 * Shown only on touch devices; desktop keeps plain mouse/keyboard input
 * (the overlay never intercepts mouse or keyboard events). Adjustable via
 * ?touchOpacity=0.1..1 and ?touchSize=0.6..2; a gear button hides/shows it.
 */
(function () {
    'use strict';
    if (window.__newcatroidTouchControls) return;
    window.__newcatroidTouchControls = true;

    // Hidden on desktop: no touch input at all.
    var isTouch = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0) ||
        (window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
    if (!isTouch) return;

    var params = new URLSearchParams(location.search);
    function num(v, lo, hi, dflt) {
        v = parseFloat(v);
        return isFinite(v) ? Math.min(hi, Math.max(lo, v)) : dflt;
    }
    var opacity = num(params.get('touchOpacity'), 0.1, 1, 0.55);
    var sizeScale = num(params.get('touchSize'), 0.6, 2, 1);

    // ---- synthetic input: dispatched to the page's own handlers ----
    function key(code, down) {
        try {
            window.dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup', {
                code: code, key: '', bubbles: true, cancelable: true
            }));
        } catch (e) { /* ignore */ }
    }
    function mouseMove(x, y, dx, dy) {
        try {
            window.dispatchEvent(new MouseEvent('mousemove', {
                clientX: Math.round(x), clientY: Math.round(y),
                movementX: Math.round(dx), movementY: Math.round(dy),
                bubbles: true, cancelable: true
            }));
        } catch (e) { /* ignore */ }
    }
    function mouseButton(target, button, down) {
        try {
            var ev = new MouseEvent(down ? 'mousedown' : 'mouseup', {
                button: button, bubbles: true, cancelable: true
            });
            (target || window).dispatchEvent(ev);
        } catch (e) { /* ignore */ }
    }

    function el(tag, css, text) {
        var e = document.createElement(tag);
        if (css) e.style.cssText = css;
        if (text != null) e.textContent = text;
        return e;
    }
    function stop(e) {
        // Prevent browser scrolling / pull-to-refresh / text selection while
        // interacting with the controls.
        e.preventDefault();
        e.stopPropagation();
    }

    var root = el('div', 'position:fixed;inset:0;pointer-events:none;z-index:2147483646;' +
        'touch-action:none;-webkit-user-select:none;user-select:none;font-family:sans-serif;');
    var hidden = false;
    try { hidden = localStorage.getItem('newcatroidTouchHidden') === '1'; } catch (e) { /* ignore */ }
    root.style.display = hidden ? 'none' : 'block';

    // ---------- left virtual joystick: movement (WASD) ----------
    var joySize = Math.round(120 * sizeScale);
    var joy = el('div', 'position:absolute;left:4vw;bottom:6vh;width:' + joySize + 'px;height:' + joySize + 'px;' +
        'border-radius:50%;background:rgba(255,255,255,' + opacity + ');' +
        'border:2px solid rgba(255,255,255,' + (opacity * 1.4) + ');pointer-events:auto;touch-action:none;');
    var knob = el('div', 'position:absolute;left:50%;top:50%;width:' + (joySize * 0.4) + 'px;height:' + (joySize * 0.4) + 'px;' +
        'margin:' + (-joySize * 0.2) + 'px 0 0 ' + (-joySize * 0.2) + 'px;border-radius:50%;' +
        'background:rgba(255,255,255,' + Math.min(1, opacity * 1.8) + ');pointer-events:none;');
    joy.appendChild(knob);
    root.appendChild(joy);

    var MOVE_MAP = { up: 'KeyW', down: 'KeyS', left: 'KeyA', right: 'KeyD' };
    var moveKeys = { up: false, down: false, left: false, right: false };
    var joyTouchId = null;
    var joyOrigin = null;

    function setMove(dir) {
        var want = {
            up: dir.y < -0.3, down: dir.y > 0.3,
            left: dir.x < -0.3, right: dir.x > 0.3
        };
        for (var k in want) {
            if (want[k] !== moveKeys[k]) {
                moveKeys[k] = want[k];
                key(MOVE_MAP[k], want[k]);
            }
        }
    }

    joy.addEventListener('touchstart', function (e) {
        stop(e);
        var t = e.changedTouches[0];
        joyTouchId = t.identifier;
        var r = joy.getBoundingClientRect();
        joyOrigin = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    }, { passive: false });
    joy.addEventListener('touchmove', function (e) {
        stop(e);
        for (var i = 0; i < e.changedTouches.length; i++) {
            var t = e.changedTouches[i];
            if (t.identifier !== joyTouchId) continue;
            var r = joy.getBoundingClientRect();
            var max = r.width / 2;
            var dx = t.clientX - joyOrigin.x;
            var dy = t.clientY - joyOrigin.y;
            var len = Math.sqrt(dx * dx + dy * dy) || 1;
            if (len > max) { dx = dx / len * max; dy = dy / len * max; }
            knob.style.left = (50 + dx / r.width * 100) + '%';
            knob.style.top = (50 + dy / r.height * 100) + '%';
            setMove({ x: dx / max, y: dy / max });
        }
    }, { passive: false });
    function endJoy(e) {
        for (var i = 0; i < e.changedTouches.length; i++) {
            if (e.changedTouches[i].identifier === joyTouchId) {
                joyTouchId = null;
                knob.style.left = '50%';
                knob.style.top = '50%';
                setMove({ x: 0, y: 0 });
            }
        }
    }
    joy.addEventListener('touchend', endJoy, { passive: false });
    joy.addEventListener('touchcancel', endJoy, { passive: false });

    // ---------- right camera/look drag area (mouse look) ----------
    var look = el('div', 'position:absolute;right:0;top:0;width:55vw;height:100vh;' +
        'pointer-events:auto;touch-action:none;');
    root.appendChild(look);
    var lookTouchId = null;
    var lookLast = null;
    look.addEventListener('touchstart', function (e) {
        stop(e);
        var t = e.changedTouches[0];
        lookTouchId = t.identifier;
        lookLast = { x: t.clientX, y: t.clientY };
    }, { passive: false });
    look.addEventListener('touchmove', function (e) {
        stop(e);
        for (var i = 0; i < e.changedTouches.length; i++) {
            var t = e.changedTouches[i];
            if (t.identifier !== lookTouchId) continue;
            // The page's unlocked mouse-look branch derives deltas from the
            // absolute position; movementX/Y also drive the locked branch.
            mouseMove(t.clientX, t.clientY,
                t.clientX - lookLast.x, t.clientY - lookLast.y);
            lookLast = { x: t.clientX, y: t.clientY };
        }
    }, { passive: false });
    function endLook(e) {
        for (var i = 0; i < e.changedTouches.length; i++) {
            if (e.changedTouches[i].identifier === lookTouchId) lookTouchId = null;
        }
    }
    look.addEventListener('touchend', endLook, { passive: false });
    look.addEventListener('touchcancel', endLook, { passive: false });

    // ---------- action buttons (standard, reliably mappable inputs) ----------
    // Jump=Space, Sprint=Shift, Crouch=Ctrl (the page's key table maps them to
    // the engine), Fire=left mouse button (BTN bit 0 in the engine's input).
    var btnSize = Math.round(64 * sizeScale);
    var cluster = el('div', 'position:absolute;right:4vw;bottom:calc(6vh + ' + (joySize + 24) + 'px);' +
        'display:flex;flex-direction:column;gap:10px;pointer-events:none;');
    root.appendChild(cluster);

    function actionButton(label, code, resolveTarget) {
        var b = el('button', 'width:' + btnSize + 'px;height:' + btnSize + 'px;border-radius:50%;' +
            'border:2px solid rgba(255,255,255,' + (opacity * 1.4) + ');' +
            'background:rgba(255,255,255,' + opacity + ');color:#fff;font-size:' +
            Math.round(11 * sizeScale) + 'px;font-weight:bold;pointer-events:auto;touch-action:none;padding:0;');
        b.textContent = label;
        var id = null;
        b.addEventListener('touchstart', function (e) {
            stop(e);
            id = e.changedTouches[0].identifier;
            if (code) key(code, true); else mouseButton(resolveTarget(), 0, true);
        }, { passive: false });
        function up(e) {
            for (var i = 0; i < e.changedTouches.length; i++) {
                if (e.changedTouches[i].identifier === id) {
                    id = null;
                    if (code) key(code, false); else mouseButton(resolveTarget(), 0, false);
                }
            }
        }
        b.addEventListener('touchend', up, { passive: false });
        b.addEventListener('touchcancel', up, { passive: false });
        cluster.appendChild(b);
    }
    actionButton('JUMP', 'Space');
    actionButton('SPRINT', 'ShiftLeft');
    actionButton('CROUCH', 'ControlLeft');
    // Fire aims/shoots with the left mouse button; the page's canvas handler
    // sets the engine's button bit. The canvas is resolved lazily because the
    // game creates it after start.
    actionButton('FIRE', null, function () {
        return document.querySelector('canvas') || window;
    });

    // ---------- gear: hide/show (persisted) ----------
    var gear = el('button', 'position:absolute;left:4vw;top:2vh;width:36px;height:36px;border-radius:8px;' +
        'border:1px solid rgba(255,255,255,' + (opacity * 1.4) + ');background:rgba(0,0,0,' +
        (opacity * 0.6) + ');color:#fff;font-size:16px;pointer-events:auto;touch-action:none;');
    gear.textContent = '⚙';
    gear.title = 'NewCatroid touch controls (tap to hide/show)';
    gear.addEventListener('touchstart', function (e) {
        stop(e);
        hidden = !hidden;
        root.style.display = hidden ? 'none' : 'block';
        try { localStorage.setItem('newcatroidTouchHidden', hidden ? '1' : '0'); } catch (err) { /* ignore */ }
    }, { passive: false });
    root.appendChild(gear);

    function mount() {
        if (document.body && !root.parentNode) document.body.appendChild(root);
    }
    if (document.body) mount();
    else document.addEventListener('DOMContentLoaded', mount);
})();
