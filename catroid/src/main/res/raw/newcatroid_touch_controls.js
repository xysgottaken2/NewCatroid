/*
 * NewCatroid optional touch controls for hosted browser games.
 *
 * The local HTTP server injects this standalone asset only when a hosted page
 * is opened with ?newcatroid_touch=1. No hosted file is edited. Normal mode
 * sends synthetic DOM input events to the page's existing handlers; edit mode
 * consumes gestures and never sends gameplay input.
 *
 * Default controls emit WASD key events, mouse movement, Space, Shift, Control,
 * and left mouse-button events. The public PlayGTA5 page accepts these input
 * events, but its game-specific meaning is inside the unavailable game module;
 * the editor exposes the page's supported key/button assignments without
 * claiming that a particular key means jump, sprint, or another game action.
 */
(function () {
    'use strict';

    if (window.__newcatroidTouchControls) return;
    window.__newcatroidTouchControls = true;

    var isTouch = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0) ||
        (window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
    if (!isTouch) return;

    var MAX_CUSTOM_BUTTONS = 12;
    var STORAGE_PREFIX = 'newcatroidTouchLayout:v1:';
    var KEY_CODES = buildKeyCodes();
    var keyCodeSet = Object.create(null);
    KEY_CODES.forEach(function (code) { keyCodeSet[code] = true; });
    var mouseButtonNames = [
        { value: 0, label: 'Left click' },
        { value: 1, label: 'Middle click' },
        { value: 2, label: 'Right click' },
        { value: 3, label: 'Back button' },
        { value: 4, label: 'Forward button' }
    ];

    function buildKeyCodes() {
        var codes = [
            'Backspace', 'Tab', 'Enter', 'NumpadEnter', 'Pause', 'CapsLock', 'Escape', 'Space',
            'PageUp', 'PageDown', 'End', 'Home', 'ArrowLeft', 'ArrowUp', 'ArrowRight', 'ArrowDown',
            'PrintScreen', 'Insert', 'Delete', 'MetaLeft', 'MetaRight', 'ContextMenu',
            'NumpadMultiply', 'NumpadAdd', 'NumpadSubtract', 'NumpadDecimal', 'NumpadDivide',
            'NumLock', 'ScrollLock', 'ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight',
            'AltLeft', 'AltRight', 'Semicolon', 'Equal', 'Comma', 'Minus', 'Period', 'Slash',
            'Backquote', 'BracketLeft', 'Backslash', 'BracketRight', 'Quote', 'IntlBackslash'
        ];
        var i;
        for (i = 0; i < 26; i++) codes.push('Key' + String.fromCharCode(65 + i));
        for (i = 0; i < 10; i++) {
            codes.push('Digit' + i);
            codes.push('Numpad' + i);
        }
        for (i = 1; i <= 24; i++) {
            // The public page deliberately lets the browser handle these keys.
            if (i !== 5 && i !== 11 && i !== 12) codes.push('F' + i);
        }
        return codes;
    }

    function finiteNumber(value, fallback) {
        var parsed = Number(value);
        return isFinite(parsed) ? parsed : fallback;
    }

    function clamp(value, low, high) {
        return Math.max(low, Math.min(high, value));
    }

    function makeStorageKey() {
        // Ignore query parameters (including the opt-in flag and game settings)
        // so each page in the same hosted game keeps one stable layout.
        var pageKey = String(location.origin || '') + String(location.pathname || '/');
        return STORAGE_PREFIX + encodeURIComponent(pageKey);
    }

    function makeDefaultLayout() {
        return {
            version: 1,
            opacity: 0.55,
            spacing: 0.14,
            hidden: false,
            controls: [
                { id: 'look', kind: 'look', x: 0.72, y: 0.50, size: 0.56, label: 'MOUSE MOVE', manual: false, custom: false },
                { id: 'joystick', kind: 'joystick', x: 0.17, y: 0.79, size: 0.32, label: 'WASD', manual: false, custom: false },
                { id: 'jump', kind: 'button', x: 0.87, y: 0.54, size: 0.17, label: 'SPACE', action: { type: 'key', code: 'Space' }, manual: false, custom: false },
                { id: 'sprint', kind: 'button', x: 0.87, y: 0.68, size: 0.17, label: 'SHIFT', action: { type: 'key', code: 'ShiftLeft' }, manual: false, custom: false },
                { id: 'crouch', kind: 'button', x: 0.87, y: 0.82, size: 0.17, label: 'CTRL', action: { type: 'key', code: 'ControlLeft' }, manual: false, custom: false },
                { id: 'fire', kind: 'button', x: 0.73, y: 0.68, size: 0.17, label: 'LCLICK', action: { type: 'mouse', button: 0 }, manual: false, custom: false }
            ]
        };
    }

    function sizeBounds(control) {
        if (control.kind === 'joystick') return { min: 0.18, max: 0.44 };
        if (control.kind === 'look') return { min: 0.32, max: 0.76 };
        return { min: 0.11, max: 0.30 };
    }

    function validAction(action, fallback) {
        if (!action || typeof action !== 'object') return fallback;
        if (action.type === 'key' && keyCodeSet[action.code]) {
            return { type: 'key', code: action.code };
        }
        if (action.type === 'mouse') {
            var button = Number(action.button);
            if (button >= 0 && button <= 4 && Math.floor(button) === button) {
                return { type: 'mouse', button: button };
            }
        }
        return fallback;
    }

    function sanitizeLayout(saved) {
        var result = makeDefaultLayout();
        if (!saved || typeof saved !== 'object') return result;

        result.opacity = clamp(finiteNumber(saved.opacity, result.opacity), 0.15, 1);
        result.spacing = clamp(finiteNumber(saved.spacing, result.spacing), 0.06, 0.28);
        result.hidden = saved.hidden === true;

        var savedControls = Array.isArray(saved.controls) ? saved.controls : [];
        var byId = Object.create(null);
        savedControls.forEach(function (control) {
            if (control && typeof control === 'object' && typeof control.id === 'string') {
                byId[control.id] = control;
            }
        });

        result.controls = result.controls.map(function (defaults) {
            var stored = byId[defaults.id];
            if (!stored) return defaults;
            var bounds = sizeBounds(defaults);
            var merged = {
                id: defaults.id,
                kind: defaults.kind,
                x: clamp(finiteNumber(stored.x, defaults.x), 0, 1),
                y: clamp(finiteNumber(stored.y, defaults.y), 0, 1),
                size: clamp(finiteNumber(stored.size, defaults.size), bounds.min, bounds.max),
                label: typeof stored.label === 'string' ? stored.label.slice(0, 16) : defaults.label,
                manual: stored.manual === true,
                custom: false
            };
            if (defaults.kind === 'button') merged.action = validAction(stored.action, defaults.action);
            return merged;
        });

        var known = Object.create(null);
        result.controls.forEach(function (control) { known[control.id] = true; });
        var customCount = 0;
        savedControls.forEach(function (stored) {
            if (!stored || typeof stored !== 'object' || typeof stored.id !== 'string' ||
                stored.id.indexOf('custom-') !== 0 || known[stored.id] || customCount >= MAX_CUSTOM_BUTTONS) return;
            var bounds = sizeBounds({ kind: 'button' });
            var action = validAction(stored.action, { type: 'key', code: 'KeyE' });
            result.controls.push({
                id: stored.id.slice(0, 80),
                kind: 'button',
                x: clamp(finiteNumber(stored.x, 0.75), 0, 1),
                y: clamp(finiteNumber(stored.y, 0.5), 0, 1),
                size: clamp(finiteNumber(stored.size, 0.17), bounds.min, bounds.max),
                label: typeof stored.label === 'string' ? stored.label.slice(0, 16) : 'NEW',
                action: action,
                manual: true,
                custom: true
            });
            known[stored.id] = true;
            customCount++;
        });
        return result;
    }

    var storageKey = makeStorageKey();
    var layout = makeDefaultLayout();
    try {
        var savedText = localStorage.getItem(storageKey);
        if (savedText) layout = sanitizeLayout(JSON.parse(savedText));
    } catch (ignoredStorageRead) {
        layout = makeDefaultLayout();
    }

    function saveLayout() {
        try { localStorage.setItem(storageKey, JSON.stringify(layout)); } catch (ignoredStorageWrite) { /* storage may be disabled */ }
    }

    function element(tag, id, text) {
        var node = document.createElement(tag);
        if (id) node.id = id;
        if (text != null) node.textContent = text;
        return node;
    }

    function setStyle(node, properties) {
        Object.keys(properties).forEach(function (key) { node.style[key] = properties[key]; });
    }

    function stopEvent(event) {
        if (event.preventDefault) event.preventDefault();
        if (event.stopPropagation) event.stopPropagation();
        if (event.stopImmediatePropagation) event.stopImmediatePropagation();
    }

    var root = element('div', 'newcatroid-touch-controls');
    setStyle(root, {
        position: 'fixed', inset: '0', zIndex: '2147483646', pointerEvents: 'none',
        touchAction: 'none', userSelect: 'none', webkitUserSelect: 'none',
        fontFamily: 'system-ui, sans-serif', color: '#fff'
    });
    root.setAttribute('aria-label', 'NewCatroid touch controls');

    var controlLayer = element('div', 'newcatroid-touch-control-layer');
    setStyle(controlLayer, { position: 'absolute', inset: '0', pointerEvents: 'none', touchAction: 'none' });
    root.appendChild(controlLayer);

    var controlElements = Object.create(null);
    var controlChildren = Object.create(null);
    var activeInputs = new Map();
    var inputCounts = new Map();
    var joystickPointerId = null;
    var joystickOrigin = null;
    var lookPointerId = null;
    var lookLast = null;
    var editDrag = null;
    var editMode = false;
    var selectedId = null;
    var addCounter = 0;
    var gear = null;
    var editorPanel = null;
    var selectedPanel = null;

    function viewportWidth() { return Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1); }
    function viewportHeight() { return Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1); }
    function shortestSide() { return Math.max(1, Math.min(viewportWidth(), viewportHeight())); }

    function geometry(control) {
        if (control.kind === 'look') return { width: viewportWidth() * control.size, height: viewportHeight() * 0.78 };
        var diameter = shortestSide() * control.size;
        return { width: diameter, height: diameter };
    }

    function clampControl(control) {
        var bounds = geometry(control);
        var margin = 8;
        var minX = (bounds.width / 2 + margin) / viewportWidth();
        var maxX = 1 - minX;
        var minY = (bounds.height / 2 + margin) / viewportHeight();
        var maxY = 1 - minY;
        control.x = clamp(finiteNumber(control.x, 0.5), Math.min(minX, 0.49), Math.max(maxX, 0.51));
        control.y = clamp(finiteNumber(control.y, 0.5), Math.min(minY, 0.49), Math.max(maxY, 0.51));
    }

    function buttonCodeLabel(code) {
        if (code === 'Space') return 'Space';
        if (code.indexOf('Key') === 0) return code.slice(3);
        if (code.indexOf('Digit') === 0) return code.slice(5);
        if (code.indexOf('Numpad') === 0 && code.length === 7) return 'Num ' + code.slice(6);
        return code;
    }

    function applyOpacity(node, control) {
        var alpha = layout.opacity;
        if (control.kind === 'joystick') {
            node.style.background = 'rgba(230,240,255,' + alpha.toFixed(2) + ')';
            node.style.borderColor = 'rgba(255,255,255,' + Math.min(1, alpha + 0.25).toFixed(2) + ')';
            var knob = controlChildren[control.id];
            if (knob) knob.style.background = 'rgba(255,255,255,' + Math.min(1, alpha + 0.35).toFixed(2) + ')';
        } else if (control.kind === 'button') {
            node.style.background = 'rgba(31,41,55,' + alpha.toFixed(2) + ')';
            node.style.borderColor = 'rgba(255,255,255,' + Math.min(1, alpha + 0.25).toFixed(2) + ')';
        }
    }

    function applyControlStyle(control) {
        var node = controlElements[control.id];
        if (!node) return;
        clampControl(control);
        var bounds = geometry(control);
        setStyle(node, {
            left: (control.x * 100) + '%',
            top: (control.y * 100) + '%',
            width: bounds.width + 'px',
            height: bounds.height + 'px',
            transform: 'translate(-50%, -50%)',
            outline: editMode ? '2px dashed rgba(255,255,255,0.85)' : 'none',
            boxSizing: 'border-box'
        });
        node.style.display = layout.hidden && !editMode ? 'none' : 'block';
        if (control.kind === 'look') {
            node.style.background = editMode ? 'rgba(55,135,255,0.12)' : 'transparent';
            node.style.border = editMode ? '2px dashed rgba(120,190,255,0.95)' : 'none';
            node.style.pointerEvents = 'auto';
            var lookLabel = controlChildren[control.id];
            if (lookLabel) lookLabel.style.display = editMode ? 'block' : 'none';
        } else {
            applyOpacity(node, control);
            node.style.pointerEvents = 'auto';
        }
        if (control.kind === 'button') {
            node.textContent = control.label || 'BUTTON';
            node.title = control.action.type === 'key' ?
                (control.label + ' · ' + buttonCodeLabel(control.action.code)) :
                (control.label + ' · ' + mouseButtonNames.filter(function (item) { return item.value === control.action.button; })[0].label);
            node.style.fontSize = Math.max(9, Math.min(18, bounds.width * 0.17)) + 'px';
        }
        if (control.kind === 'joystick') {
            node.style.borderRadius = '50%';
            node.style.border = '2px solid rgba(255,255,255,0.8)';
            var knob = controlChildren[control.id];
            if (knob) {
                var knobSize = bounds.width * 0.40;
                setStyle(knob, {
                    width: knobSize + 'px', height: knobSize + 'px', borderRadius: '50%',
                    position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%, -50%)',
                    pointerEvents: 'none'
                });
            }
        }
        node.style.zIndex = control.kind === 'look' ? '1' : (control.kind === 'joystick' ? '3' : '4');
    }

    function renderLayout() {
        layout.controls.forEach(applyControlStyle);
        if (gear) gear.style.display = 'block';
        if (editorPanel) editorPanel.style.display = editMode ? 'block' : 'none';
        if (controlLayer) controlLayer.style.display = layout.hidden && !editMode ? 'none' : 'block';
    }

    function getControl(id) {
        for (var i = 0; i < layout.controls.length; i++) {
            if (layout.controls[i].id === id) return layout.controls[i];
        }
        return null;
    }

    function createControlElement(control) {
        var node;
        if (control.kind === 'button') {
            node = element('button', 'newcatroid-control-' + control.id, control.label);
            node.type = 'button';
            setStyle(node, {
                position: 'absolute', padding: '2px', borderRadius: '50%', border: '2px solid #fff',
                color: '#fff', fontWeight: '700', textAlign: 'center', lineHeight: '1.1',
                touchAction: 'none', pointerEvents: 'auto', boxShadow: '0 2px 10px rgba(0,0,0,0.35)'
            });
        } else {
            node = element('div', 'newcatroid-control-' + control.id);
            setStyle(node, { position: 'absolute', touchAction: 'none', pointerEvents: 'auto' });
            if (control.kind === 'joystick') {
                var knob = element('div', 'newcatroid-control-' + control.id + '-knob');
                node.appendChild(knob);
                controlChildren[control.id] = knob;
            } else if (control.kind === 'look') {
                var label = element('div', 'newcatroid-control-' + control.id + '-label', 'MOUSE MOVE');
                setStyle(label, {
                    position: 'absolute', inset: '0', display: 'none', textAlign: 'center',
                    paddingTop: '12px', color: '#fff', fontSize: '12px', fontWeight: '700',
                    textShadow: '0 1px 3px #000', pointerEvents: 'none'
                });
                node.appendChild(label);
                controlChildren[control.id] = label;
            }
        }
        controlElements[control.id] = node;
        controlLayer.appendChild(node);
        bindControlEvents(control, node);
        applyControlStyle(control);
        return node;
    }

    function ensureControlElements() {
        layout.controls.forEach(function (control) {
            if (!controlElements[control.id]) createControlElement(control);
        });
    }

    function keyEvent(code, down) {
        try {
            window.dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup', {
                code: code, key: '', bubbles: true, cancelable: true
            }));
        } catch (ignoredKeyboardEvent) { /* unsupported constructor */ }
    }

    function mouseEvent(type, button, x, y) {
        var target = type === 'mousedown' ? (document.querySelector('canvas') || window) : window;
        try {
            target.dispatchEvent(new MouseEvent(type, {
                button: button, clientX: Math.round(x || 0), clientY: Math.round(y || 0),
                bubbles: true, cancelable: true
            }));
        } catch (ignoredMouseEvent) { /* unsupported constructor */ }
    }

    function inputKey(action) {
        return action.type === 'key' ? 'key:' + action.code : 'mouse:' + action.button;
    }

    function pressInput(pointerId, control) {
        if (activeInputs.has(pointerId) || !control || !control.action) return;
        var action = control.action;
        var key = inputKey(action);
        var count = inputCounts.get(key) || 0;
        inputCounts.set(key, count + 1);
        activeInputs.set(pointerId, { key: key, action: action, controlId: control.id });
        if (count === 0) {
            if (action.type === 'key') keyEvent(action.code, true);
            else mouseEvent('mousedown', action.button, 0, 0);
        }
    }

    function releaseInput(pointerId) {
        var active = activeInputs.get(pointerId);
        if (!active) return;
        activeInputs.delete(pointerId);
        var count = Math.max(0, (inputCounts.get(active.key) || 1) - 1);
        if (count === 0) {
            inputCounts.delete(active.key);
            if (active.action.type === 'key') keyEvent(active.action.code, false);
            else mouseEvent('mouseup', active.action.button, 0, 0);
        } else {
            inputCounts.set(active.key, count);
        }
    }

    function releaseControlInputs(controlId) {
        Array.from(activeInputs.entries()).forEach(function (entry) {
            if (entry[1].controlId === controlId) releaseInput(entry[0]);
        });
    }

    function releaseAllInputs() {
        Array.from(activeInputs.keys()).forEach(releaseInput);
        if (joystickPointerId !== null) {
            joystickPointerId = null;
            joystickOrigin = null;
        }
        lookPointerId = null;
        lookLast = null;
        editDrag = null;
        setJoystickKeys({ x: 0, y: 0 });
        var knob = controlChildren.joystick;
        if (knob) knob.style.left = '50%';
        if (knob) knob.style.top = '50%';
    }

    var joystickDirections = { up: 'KeyW', down: 'KeyS', left: 'KeyA', right: 'KeyD' };
    var joystickKeys = { up: false, down: false, left: false, right: false };

    function setJoystickKeys(vector) {
        var wanted = {
            up: vector.y < -0.30,
            down: vector.y > 0.30,
            left: vector.x < -0.30,
            right: vector.x > 0.30
        };
        Object.keys(wanted).forEach(function (direction) {
            if (wanted[direction] === joystickKeys[direction]) return;
            joystickKeys[direction] = wanted[direction];
            var id = 'newcatroid-joystick-' + direction;
            var control = { id: 'joystick-key', action: { type: 'key', code: joystickDirections[direction] } };
            if (wanted[direction]) pressInput(id, control);
            else releaseInput(id);
        });
    }

    function pointerId(event) {
        return event.pointerId == null ? 'pointer-default' : event.pointerId;
    }

    function capturePointer(node, event) {
        try { if (node.setPointerCapture && event.pointerId != null) node.setPointerCapture(event.pointerId); } catch (ignoredCapture) {}
    }

    function beginEditorDrag(control, node, event) {
        stopEvent(event);
        capturePointer(node, event);
        selectedId = control.id;
        editDrag = {
            pointerId: pointerId(event),
            controlId: control.id,
            startX: finiteNumber(event.clientX, 0),
            startY: finiteNumber(event.clientY, 0),
            startControlX: control.x,
            startControlY: control.y,
            moved: false
        };
        updateEditorSelection();
    }

    function moveEditorDrag(control, event) {
        if (!editDrag || editDrag.pointerId !== pointerId(event) || editDrag.controlId !== control.id) return;
        stopEvent(event);
        var dx = (finiteNumber(event.clientX, editDrag.startX) - editDrag.startX) / viewportWidth();
        var dy = (finiteNumber(event.clientY, editDrag.startY) - editDrag.startY) / viewportHeight();
        if (Math.abs(dx) + Math.abs(dy) > 0.003) editDrag.moved = true;
        control.x = editDrag.startControlX + dx;
        control.y = editDrag.startControlY + dy;
        if (control.kind === 'button' && !control.custom) control.manual = true;
        applyControlStyle(control);
    }

    function endEditorDrag(control, event) {
        if (!editDrag || editDrag.pointerId !== pointerId(event) || editDrag.controlId !== control.id) return;
        stopEvent(event);
        editDrag = null;
        clampControl(control);
        applyControlStyle(control);
        saveLayout();
        updateEditorSelection();
    }

    function bindControlEvents(control, node) {
        node.addEventListener('pointerdown', function (event) {
            if (editMode) {
                beginEditorDrag(control, node, event);
                return;
            }
            if (layout.hidden) return;
            stopEvent(event);
            capturePointer(node, event);
            if (control.kind === 'joystick') {
                if (joystickPointerId !== null) return;
                joystickPointerId = pointerId(event);
                joystickOrigin = { x: finiteNumber(event.clientX, 0), y: finiteNumber(event.clientY, 0) };
            } else if (control.kind === 'look') {
                if (lookPointerId !== null) return;
                lookPointerId = pointerId(event);
                lookLast = { x: finiteNumber(event.clientX, 0), y: finiteNumber(event.clientY, 0) };
            } else {
                pressInput(pointerId(event), control);
            }
        }, { passive: false });

        node.addEventListener('pointermove', function (event) {
            if (editMode) {
                moveEditorDrag(control, event);
                return;
            }
            if (control.kind === 'joystick' && joystickPointerId === pointerId(event) && joystickOrigin) {
                stopEvent(event);
                var bounds = geometry(control);
                var radius = Math.max(1, bounds.width / 2);
                var dx = finiteNumber(event.clientX, joystickOrigin.x) - joystickOrigin.x;
                var dy = finiteNumber(event.clientY, joystickOrigin.y) - joystickOrigin.y;
                var length = Math.sqrt(dx * dx + dy * dy) || 1;
                if (length > radius) { dx = dx / length * radius; dy = dy / length * radius; }
                var knob = controlChildren[control.id];
                if (knob) {
                    knob.style.left = (50 + dx / bounds.width * 100) + '%';
                    knob.style.top = (50 + dy / bounds.height * 100) + '%';
                }
                setJoystickKeys({ x: dx / radius, y: dy / radius });
            } else if (control.kind === 'look' && lookPointerId === pointerId(event) && lookLast) {
                stopEvent(event);
                var x = finiteNumber(event.clientX, lookLast.x);
                var y = finiteNumber(event.clientY, lookLast.y);
                dispatchLookMove(x, y, x - lookLast.x, y - lookLast.y);
                lookLast = { x: x, y: y };
            }
        }, { passive: false });

        function end(event) {
            if (editMode) {
                endEditorDrag(control, event);
                return;
            }
            if (control.kind === 'joystick' && joystickPointerId === pointerId(event)) {
                stopEvent(event);
                joystickPointerId = null;
                joystickOrigin = null;
                setJoystickKeys({ x: 0, y: 0 });
                var knob = controlChildren[control.id];
                if (knob) { knob.style.left = '50%'; knob.style.top = '50%'; }
            } else if (control.kind === 'look' && lookPointerId === pointerId(event)) {
                stopEvent(event);
                lookPointerId = null;
                lookLast = null;
            } else {
                releaseInput(pointerId(event));
            }
        }
        node.addEventListener('pointerup', end, { passive: false });
        node.addEventListener('pointercancel', end, { passive: false });
        node.addEventListener('lostpointercapture', end, { passive: false });
    }

    function dispatchLookMove(x, y, dx, dy) {
        try {
            window.dispatchEvent(new MouseEvent('mousemove', {
                clientX: Math.round(x), clientY: Math.round(y),
                movementX: Math.round(dx), movementY: Math.round(dy),
                bubbles: true, cancelable: true
            }));
        } catch (ignoredMouseMove) { /* unsupported constructor */ }
    }

    function createButton(id, label, onClick) {
        var button = element('button', id, label);
        button.type = 'button';
        setStyle(button, {
            pointerEvents: 'auto', touchAction: 'manipulation', margin: '3px', padding: '7px 10px',
            border: '1px solid rgba(255,255,255,0.75)', borderRadius: '7px',
            color: '#fff', background: 'rgba(20,28,38,0.94)', fontSize: '13px', fontWeight: '600'
        });
        button.addEventListener('click', function (event) {
            if (event.preventDefault) event.preventDefault();
            onClick();
        });
        return button;
    }

    function createFieldLabel(text, id) {
        var label = element('label', id + '-label', text);
        setStyle(label, { display: 'inline-flex', alignItems: 'center', gap: '5px', margin: '4px', fontSize: '12px' });
        return label;
    }

    function createSelect(id, values, labels) {
        var select = element('select', id);
        select.id = id;
        setStyle(select, { maxWidth: '155px', minHeight: '30px', fontSize: '12px' });
        values.forEach(function (value, index) {
            var option = element('option', null, labels[index]);
            option.value = String(value);
            select.appendChild(option);
        });
        return select;
    }

    function applySpacingToUntouchedDefaults() {
        var center = 0.68;
        var byId = { jump: center - layout.spacing, sprint: center, crouch: center + layout.spacing };
        layout.controls.forEach(function (control) {
            if (!control.custom && !control.manual && byId[control.id] != null) control.y = byId[control.id];
        });
    }

    function ensureEditor() {
        if (editorPanel) return;
        editorPanel = element('section', 'newcatroid-touch-editor');
        setStyle(editorPanel, {
            position: 'fixed', top: '8px', left: '50%', transform: 'translateX(-50%)',
            width: 'min(94vw, 620px)', maxHeight: '42vh', overflowY: 'auto',
            padding: '8px', boxSizing: 'border-box', borderRadius: '10px',
            border: '1px solid rgba(255,255,255,0.85)', background: 'rgba(15,20,28,0.94)',
            color: '#fff', pointerEvents: 'auto', zIndex: '2147483647',
            fontSize: '12px', boxShadow: '0 4px 18px rgba(0,0,0,0.45)'
        });
        editorPanel.setAttribute('aria-label', 'Touch controls editor');

        var heading = element('div', 'newcatroid-touch-editor-heading', 'Touch controls editor · drag a control to move it');
        setStyle(heading, { fontWeight: '700', padding: '2px 4px 5px' });
        editorPanel.appendChild(heading);

        var toolbar = element('div', 'newcatroid-touch-editor-toolbar');
        editorPanel.appendChild(toolbar);
        toolbar.appendChild(createButton('newcatroid-touch-add', 'Add button', addCustomButton));
        toolbar.appendChild(createButton('newcatroid-touch-reset', 'Restore defaults', restoreDefaults));
        toolbar.appendChild(createButton('newcatroid-touch-toggle', 'Hide controls', toggleControls));
        toolbar.appendChild(createButton('newcatroid-touch-done', 'Done', exitEditMode));

        selectedPanel = element('div', 'newcatroid-touch-editor-selected');
        setStyle(selectedPanel, { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '2px', borderTop: '1px solid rgba(255,255,255,0.2)', marginTop: '5px', paddingTop: '5px' });
        editorPanel.appendChild(selectedPanel);
        root.appendChild(editorPanel);
        updateEditorSelection();
    }

    function addRangeField(parent, id, labelText, min, max, value, onInput) {
        var label = createFieldLabel(labelText, id);
        var input = element('input', id);
        input.type = 'range';
        input.min = String(min);
        input.max = String(max);
        input.step = '1';
        input.value = String(value);
        setStyle(input, { width: '115px' });
        input.addEventListener('input', function () { onInput(Number(input.value)); });
        label.appendChild(input);
        parent.appendChild(label);
        return input;
    }

    function updateEditorSelection() {
        if (!selectedPanel) return;
        while (selectedPanel.children.length) selectedPanel.removeChild(selectedPanel.children[0]);
        var selected = getControl(selectedId);
        if (!selected) selected = getControl('joystick');
        if (!selected) return;
        selectedId = selected.id;

        var selectorLabel = createFieldLabel('Selected', 'newcatroid-touch-selected');
        var controlSelect = element('select', 'newcatroid-touch-selected-control');
        layout.controls.forEach(function (control) {
            var option = element('option', null, control.label || control.id);
            option.value = control.id;
            controlSelect.appendChild(option);
        });
        controlSelect.value = selected.id;
        setStyle(controlSelect, { maxWidth: '150px', minHeight: '30px', fontSize: '12px' });
        controlSelect.addEventListener('change', function () {
            selectedId = controlSelect.value;
            updateEditorSelection();
        });
        selectorLabel.appendChild(controlSelect);
        selectedPanel.appendChild(selectorLabel);

        if (selected.kind === 'button') {
            var labelField = createFieldLabel('Label', 'newcatroid-touch-label-field');
            var labelInput = element('input', 'newcatroid-touch-label');
            labelInput.type = 'text';
            labelInput.maxLength = 16;
            labelInput.value = selected.label;
            setStyle(labelInput, { width: '90px', minHeight: '28px', fontSize: '12px' });
            labelInput.addEventListener('input', function () {
                selected.label = String(labelInput.value || '').slice(0, 16);
                applyControlStyle(selected);
                saveLayout();
            });
            labelField.appendChild(labelInput);
            selectedPanel.appendChild(labelField);

            var actionField = createFieldLabel('Action', 'newcatroid-touch-action-field');
            var actionSelect = createSelect('newcatroid-touch-action', ['key', 'mouse'], ['Keyboard key', 'Mouse button']);
            actionSelect.value = selected.action.type;
            actionSelect.addEventListener('change', function () {
                if (selected.action.type === 'key') releaseControlInputs(selected.id);
                selected.action = actionSelect.value === 'mouse' ? { type: 'mouse', button: 0 } : { type: 'key', code: 'KeyE' };
                saveLayout();
                updateEditorSelection();
            });
            actionField.appendChild(actionSelect);
            selectedPanel.appendChild(actionField);

            if (selected.action.type === 'key') {
                var keyField = createFieldLabel('Key', 'newcatroid-touch-key-field');
                var keySelect = createSelect('newcatroid-touch-key', KEY_CODES, KEY_CODES.map(buttonCodeLabel));
                keySelect.value = selected.action.code;
                keySelect.addEventListener('change', function () {
                    releaseControlInputs(selected.id);
                    selected.action = { type: 'key', code: keySelect.value };
                    applyControlStyle(selected);
                    saveLayout();
                });
                keyField.appendChild(keySelect);
                selectedPanel.appendChild(keyField);
            } else {
                var mouseField = createFieldLabel('Mouse', 'newcatroid-touch-mouse-field');
                var mouseValues = mouseButtonNames.map(function (item) { return item.value; });
                var mouseLabels = mouseButtonNames.map(function (item) { return item.label; });
                var mouseSelect = createSelect('newcatroid-touch-mouse', mouseValues, mouseLabels);
                mouseSelect.value = String(selected.action.button);
                mouseSelect.addEventListener('change', function () {
                    releaseControlInputs(selected.id);
                    selected.action = { type: 'mouse', button: Number(mouseSelect.value) };
                    applyControlStyle(selected);
                    saveLayout();
                });
                mouseField.appendChild(mouseSelect);
                selectedPanel.appendChild(mouseField);
            }
        }

        var sizeLimits = sizeBounds(selected);
        addRangeField(selectedPanel, 'newcatroid-touch-size', 'Size', Math.round(sizeLimits.min * 100), Math.round(sizeLimits.max * 100), Math.round(selected.size * 100), function (value) {
            selected.size = clamp(value / 100, sizeLimits.min, sizeLimits.max);
            clampControl(selected);
            applyControlStyle(selected);
            saveLayout();
        });

        if (selected.custom) {
            selectedPanel.appendChild(createButton('newcatroid-touch-remove', 'Remove selected', function () {
                removeCustomButton(selected.id);
            }));
        }

        addRangeField(selectedPanel, 'newcatroid-touch-opacity', 'Opacity', 15, 100, Math.round(layout.opacity * 100), function (value) {
            layout.opacity = clamp(value / 100, 0.15, 1);
            layout.controls.forEach(applyControlStyle);
            applyOpacity(gear, { kind: 'button' });
            saveLayout();
        });
        addRangeField(selectedPanel, 'newcatroid-touch-spacing', 'Default spacing', 6, 28, Math.round(layout.spacing * 100), function (value) {
            layout.spacing = clamp(value / 100, 0.06, 0.28);
            applySpacingToUntouchedDefaults();
            layout.controls.forEach(applyControlStyle);
            saveLayout();
        });
    }

    function addCustomButton() {
        var customCount = layout.controls.filter(function (control) { return control.custom; }).length;
        if (customCount >= MAX_CUSTOM_BUTTONS) return;
        addCounter++;
        var control = {
            id: 'custom-' + Date.now().toString(36) + '-' + addCounter,
            kind: 'button', x: 0.72, y: 0.43 + (customCount % 4) * 0.08,
            size: 0.17, label: 'NEW', action: { type: 'key', code: 'KeyE' },
            manual: true, custom: true
        };
        layout.controls.push(control);
        createControlElement(control);
        selectedId = control.id;
        clampControl(control);
        applyControlStyle(control);
        saveLayout();
        updateEditorSelection();
    }

    function removeCustomButton(id) {
        var control = getControl(id);
        if (!control || !control.custom) return;
        releaseControlInputs(id);
        var node = controlElements[id];
        if (node && node.parentNode) node.parentNode.removeChild(node);
        delete controlElements[id];
        delete controlChildren[id];
        layout.controls = layout.controls.filter(function (item) { return item.id !== id; });
        selectedId = 'joystick';
        saveLayout();
        updateEditorSelection();
    }

    function toggleControls() {
        layout.hidden = !layout.hidden;
        var toggle = document.getElementById('newcatroid-touch-toggle');
        if (toggle) toggle.textContent = layout.hidden ? 'Show controls' : 'Hide controls';
        if (layout.hidden) releaseAllInputs();
        renderLayout();
        saveLayout();
    }

    function restoreDefaults() {
        releaseAllInputs();
        var oldControls = layout.controls.slice();
        oldControls.forEach(function (control) {
            var node = controlElements[control.id];
            if (node && node.parentNode) node.parentNode.removeChild(node);
            delete controlElements[control.id];
            delete controlChildren[control.id];
        });
        layout = makeDefaultLayout();
        selectedId = 'joystick';
        ensureControlElements();
        renderLayout();
        saveLayout();
        updateEditorSelection();
    }

    function enterEditMode() {
        if (layout.hidden) layout.hidden = false;
        releaseAllInputs();
        editMode = true;
        selectedId = selectedId || 'joystick';
        ensureEditor();
        renderLayout();
        saveLayout();
    }

    function exitEditMode() {
        editMode = false;
        editDrag = null;
        renderLayout();
        saveLayout();
    }

    function bindGear() {
        gear = element('button', 'newcatroid-touch-settings', '⚙');
        gear.type = 'button';
        gear.title = 'Edit touch controls';
        gear.setAttribute('aria-label', 'Edit touch controls');
        setStyle(gear, {
            position: 'absolute', left: 'max(8px, 3vw)', top: 'max(8px, 2vh)',
            width: '42px', height: '42px', padding: '0', borderRadius: '9px',
            border: '1px solid rgba(255,255,255,0.8)', color: '#fff',
            fontSize: '22px', lineHeight: '1', background: 'rgba(10,15,20,0.75)',
            pointerEvents: 'auto', touchAction: 'manipulation', zIndex: '2147483647'
        });
        gear.addEventListener('click', function (event) {
            if (event.preventDefault) event.preventDefault();
            if (editMode) exitEditMode();
            else enterEditMode();
        });
        gear.addEventListener('pointerdown', function (event) {
            if (event.stopPropagation) event.stopPropagation();
        });
        root.appendChild(gear);
        applyOpacity(gear, { kind: 'button' });
    }

    function start() {
        if (!document.body || root.parentNode) return;
        bindGear();
        ensureControlElements();
        document.body.appendChild(root);
        renderLayout();
    }

    window.addEventListener('resize', function () {
        layout.controls.forEach(function (control) {
            clampControl(control);
            applyControlStyle(control);
        });
        saveLayout();
    });
    window.addEventListener('orientationchange', function () {
        layout.controls.forEach(function (control) {
            clampControl(control);
            applyControlStyle(control);
        });
        saveLayout();
    });
    window.addEventListener('blur', releaseAllInputs);
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') releaseAllInputs();
    });
    window.addEventListener('pointerup', function (event) { releaseInput(pointerId(event)); }, true);
    window.addEventListener('pointercancel', function (event) { releaseInput(pointerId(event)); }, true);
    document.addEventListener('contextmenu', function (event) {
        if (!editMode) stopEvent(event);
    }, { passive: false });

    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start, { once: true });
})();
