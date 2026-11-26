const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const scriptPath = path.join(__dirname, '../../main/res/raw/newcatroid_touch_controls.js');
const source = fs.readFileSync(scriptPath, 'utf8');

class FakeEvent {
    constructor(type, init = {}) {
        this.type = type;
        this.bubbles = init.bubbles !== false;
        this.cancelable = init.cancelable !== false;
        this.defaultPrevented = false;
        this._stopped = false;
        this._immediateStopped = false;
        Object.assign(this, init);
    }
    preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
    stopPropagation() { this._stopped = true; }
    stopImmediatePropagation() { this._stopped = true; this._immediateStopped = true; }
}

class FakeEventTarget {
    constructor() { this._listeners = Object.create(null); }
    addEventListener(type, callback, options) {
        if (!this._listeners[type]) this._listeners[type] = [];
        this._listeners[type].push({ callback, once: !!(options && options.once) });
    }
    removeEventListener(type, callback) {
        this._listeners[type] = (this._listeners[type] || []).filter((item) => item.callback !== callback);
    }
    dispatchEvent(event) {
        if (!(event instanceof FakeEvent)) event = Object.assign(new FakeEvent(event.type || event), event);
        if (!event.target) event.target = this;
        let target = this;
        while (target) {
            event.currentTarget = target;
            const listeners = (target._listeners && target._listeners[event.type]) || [];
            for (const item of [...listeners]) {
                item.callback.call(target, event);
                if (item.once) target.removeEventListener(event.type, item.callback);
                if (event._immediateStopped) break;
            }
            if (!event.bubbles || event._stopped) break;
            target = target.parentNode || target._eventParent || null;
        }
        return !event.defaultPrevented;
    }
}

class FakeElement extends FakeEventTarget {
    constructor(tagName, ownerDocument) {
        super();
        this.tagName = tagName.toUpperCase();
        this.ownerDocument = ownerDocument;
        this.children = [];
        this.parentNode = null;
        this.style = Object.create(null);
        this.attributes = Object.create(null);
        this.dataset = Object.create(null);
        this.id = '';
        this.type = '';
        this.value = '';
        this.textContent = '';
        this.title = '';
    }
    appendChild(child) {
        if (child.parentNode) child.parentNode.removeChild(child);
        child.parentNode = this;
        this.children.push(child);
        if (this.tagName === 'SELECT' && child.tagName === 'OPTION' && !this.value) this.value = child.value;
        return child;
    }
    removeChild(child) {
        const index = this.children.indexOf(child);
        if (index >= 0) this.children.splice(index, 1);
        child.parentNode = null;
        return child;
    }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    getAttribute(name) { return this.attributes[name] || null; }
    setPointerCapture(pointerId) { this.lastCapturedPointerId = pointerId; }
    querySelector(selector) {
        if (selector === 'canvas') return this.ownerDocument.findByTag(this, 'CANVAS');
        return null;
    }
    getBoundingClientRect() {
        return { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 };
    }
    click() { this.dispatchEvent(new FakeEvent('click', { bubbles: true, cancelable: true })); }
}

class FakeDocument extends FakeEventTarget {
    constructor() {
        super();
        this.visibilityState = 'visible';
        this.documentElement = { clientWidth: 900, clientHeight: 600 };
        this.body = new FakeElement('body', this);
        this.body.parentNode = this;
        this._eventParent = null;
    }
    createElement(tagName) { return new FakeElement(tagName, this); }
    getElementById(id) { return this.findById(this.body, id); }
    findById(node, id) {
        if (node.id === id) return node;
        for (const child of node.children) {
            const found = this.findById(child, id);
            if (found) return found;
        }
        return null;
    }
    findByTag(node, tagName) {
        if (node.tagName === tagName) return node;
        for (const child of node.children) {
            const found = this.findByTag(child, tagName);
            if (found) return found;
        }
        return null;
    }
    querySelector(selector) {
        if (selector === 'canvas') return this.findByTag(this.body, 'CANVAS');
        return null;
    }
}

class FakeStorage {
    constructor(seed) { this.values = new Map(seed || []); }
    getItem(key) { return this.values.has(key) ? this.values.get(key) : null; }
    setItem(key, value) { this.values.set(String(key), String(value)); }
    removeItem(key) { this.values.delete(key); }
    entries() { return [...this.values.entries()]; }
}

function createBrowser({ storage = new FakeStorage(), origin = 'http://127.0.0.1:8000', pathname = '/game/index.html', touch = true } = {}) {
    const document = new FakeDocument();
    const window = new FakeEventTarget();
    window.parentNode = null;
    window.innerWidth = 900;
    window.innerHeight = 600;
    if (touch) window.ontouchstart = null;
    window.matchMedia = () => ({ matches: touch });
    window.document = document;
    document._eventParent = window;
    document.defaultView = window;

    const keyboardEvents = [];
    const mouseEvents = [];
    window.addEventListener('keydown', (event) => keyboardEvents.push({ type: event.type, code: event.code }));
    window.addEventListener('keyup', (event) => keyboardEvents.push({ type: event.type, code: event.code }));
    window.addEventListener('mousemove', (event) => mouseEvents.push({ type: event.type, movementX: event.movementX, movementY: event.movementY }));

    const canvas = document.createElement('canvas');
    canvas.id = 'game-canvas';
    document.body.appendChild(canvas);
    canvas.addEventListener('mousedown', (event) => mouseEvents.push({ type: event.type, button: event.button }));
    window.addEventListener('mouseup', (event) => mouseEvents.push({ type: event.type, button: event.button }));

    class KeyboardEvent extends FakeEvent {
        constructor(type, init = {}) { super(type, init); this.code = init.code || ''; this.key = init.key || ''; }
    }
    class MouseEvent extends FakeEvent {
        constructor(type, init = {}) {
            super(type, init);
            this.button = init.button || 0;
            this.clientX = init.clientX || 0;
            this.clientY = init.clientY || 0;
            this.movementX = init.movementX || 0;
            this.movementY = init.movementY || 0;
        }
    }

    const context = {
        window,
        document,
        navigator: { maxTouchPoints: touch ? 1 : 0 },
        location: { origin, pathname, search: '?newcatroid_touch=1' },
        localStorage: storage,
        KeyboardEvent,
        MouseEvent,
        URLSearchParams,
        Date,
        Map,
        Set,
        Array,
        Object,
        JSON,
        Number,
        Math,
        String,
        isFinite,
        console: { log() {}, warn() {}, error() {} }
    };
    vm.runInNewContext(source, context, { filename: 'newcatroid_touch_controls.js' });
    return { window, document, storage, keyboardEvents, mouseEvents };
}

function pointer(target, type, pointerId, clientX, clientY) {
    return target.dispatchEvent(new FakeEvent(type, {
        pointerId, clientX, clientY, pointerType: 'touch', bubbles: true, cancelable: true
    }));
}

function layoutFrom(storage, origin = 'http://127.0.0.1:8000', pathname = '/game/index.html') {
    const expectedKey = 'newcatroidTouchLayout:v1:' + encodeURIComponent(origin + pathname);
    const entry = storage.entries().find(([key]) => key === expectedKey);
    assert.ok(entry, `expected a saved layout for ${pathname}`);
    return JSON.parse(entry[1]);
}

function controlById(document, id) {
    const node = document.getElementById('newcatroid-control-' + id);
    assert.ok(node, `missing control ${id}`);
    return node;
}

function click(document, id) {
    const node = document.getElementById(id);
    assert.ok(node, `missing button ${id}`);
    node.click();
    return node;
}

test('default controls send real key, mouse, camera, and joystick events with multitouch-safe releases', () => {
    const { window, document, keyboardEvents, mouseEvents } = createBrowser();
    const jump = controlById(document, 'jump');
    const fire = controlById(document, 'fire');
    const look = controlById(document, 'look');
    const joystick = controlById(document, 'joystick');

    pointer(jump, 'pointerdown', 1, 700, 300);
    pointer(fire, 'pointerdown', 2, 650, 400);
    assert.deepEqual(keyboardEvents, [{ type: 'keydown', code: 'Space' }]);
    assert.deepEqual(mouseEvents.filter((event) => event.type === 'mousedown'), [{ type: 'mousedown', button: 0 }]);

    pointer(jump, 'pointerup', 1, 700, 300);
    assert.deepEqual(keyboardEvents.map((event) => event.type), ['keydown', 'keyup']);
    assert.equal(mouseEvents.filter((event) => event.type === 'mouseup').length, 0, 'fire remains held until its own pointer is released');
    pointer(fire, 'pointerup', 2, 650, 400);
    assert.equal(mouseEvents.filter((event) => event.type === 'mouseup').length, 1);

    pointer(look, 'pointerdown', 3, 100, 100);
    pointer(look, 'pointermove', 3, 124, 113);
    pointer(look, 'pointerup', 3, 124, 113);
    assert.ok(mouseEvents.some((event) => event.type === 'mousemove' && event.movementX === 24 && event.movementY === 13));

    pointer(joystick, 'pointerdown', 4, 100, 400);
    pointer(joystick, 'pointermove', 4, 145, 400);
    assert.ok(keyboardEvents.some((event) => event.type === 'keydown' && event.code === 'KeyD'));
    pointer(joystick, 'pointerup', 4, 145, 400);
    assert.ok(keyboardEvents.some((event) => event.type === 'keyup' && event.code === 'KeyD'));

    window.dispatchEvent(new FakeEvent('blur'));
    assert.equal(keyboardEvents.filter((event) => event.type === 'keyup' && event.code === 'Space').length, 1);
});

test('opening the editor releases held game inputs so a control cannot stay stuck', () => {
    const { document, keyboardEvents, mouseEvents } = createBrowser();
    const jump = controlById(document, 'jump');
    const fire = controlById(document, 'fire');
    pointer(jump, 'pointerdown', 90, 700, 300);
    pointer(fire, 'pointerdown', 91, 650, 400);
    assert.equal(keyboardEvents.at(-1).type, 'keydown');
    assert.equal(mouseEvents.at(-1).type, 'mousedown');

    click(document, 'newcatroid-touch-settings');
    assert.deepEqual(keyboardEvents.map((event) => event.type), ['keydown', 'keyup']);
    assert.deepEqual(mouseEvents.filter((event) => event.type === 'mousedown' || event.type === 'mouseup').map((event) => event.type), ['mousedown', 'mouseup']);
    pointer(jump, 'pointerup', 90, 700, 300);
    pointer(fire, 'pointerup', 91, 650, 400);
    click(document, 'newcatroid-touch-done');
    assert.equal(keyboardEvents.filter((event) => event.type === 'keydown').length, 1);
});

test('editor supports drag, resize, opacity, spacing, custom key and mouse assignments, removal, and suppresses gameplay while editing', () => {
    const { document, keyboardEvents, mouseEvents, storage } = createBrowser();
    click(document, 'newcatroid-touch-settings');
    assert.ok(document.getElementById('newcatroid-touch-editor'));

    const jump = controlById(document, 'jump');
    const before = layoutFrom(storage).controls.find((control) => control.id === 'jump');
    pointer(jump, 'pointerdown', 10, 700, 300);
    pointer(jump, 'pointermove', 10, 890, 40);
    pointer(jump, 'pointerup', 10, 890, 40);
    const moved = layoutFrom(storage).controls.find((control) => control.id === 'jump');
    assert.notEqual(moved.x, before.x);
    assert.notEqual(moved.y, before.y);
    assert.equal(moved.manual, true);
    assert.equal(keyboardEvents.length, 0, 'editing drag must not press gameplay keys');

    const size = document.getElementById('newcatroid-touch-size');
    size.value = '24';
    size.dispatchEvent(new FakeEvent('input'));
    assert.equal(layoutFrom(storage).controls.find((control) => control.id === 'jump').size, 0.24);

    const opacity = document.getElementById('newcatroid-touch-opacity');
    opacity.value = '88';
    opacity.dispatchEvent(new FakeEvent('input'));
    assert.equal(layoutFrom(storage).opacity, 0.88);

    const spacing = document.getElementById('newcatroid-touch-spacing');
    spacing.value = '22';
    spacing.dispatchEvent(new FakeEvent('input'));
    assert.equal(layoutFrom(storage).spacing, 0.22);

    click(document, 'newcatroid-touch-add');
    const selected = document.getElementById('newcatroid-touch-selected-control');
    const customId = selected.value;
    assert.ok(customId.startsWith('custom-'));

    const label = document.getElementById('newcatroid-touch-label');
    label.value = 'ACTION';
    label.dispatchEvent(new FakeEvent('input'));
    const key = document.getElementById('newcatroid-touch-key');
    const offeredKeys = key.children.map((option) => option.value);
    assert.ok(offeredKeys.includes('Numpad8'));
    assert.ok(offeredKeys.includes('KeyQ'));
    assert.ok(!offeredKeys.includes('F5') && !offeredKeys.includes('F11') && !offeredKeys.includes('F12'));
    key.value = 'KeyQ';
    key.dispatchEvent(new FakeEvent('change'));
    assert.equal(layoutFrom(storage).controls.find((control) => control.id === customId).action.code, 'KeyQ');

    click(document, 'newcatroid-touch-done');
    pointer(controlById(document, customId), 'pointerdown', 11, 500, 220);
    pointer(controlById(document, customId), 'pointerup', 11, 500, 220);
    assert.ok(keyboardEvents.some((event) => event.type === 'keydown' && event.code === 'KeyQ'));
    assert.ok(keyboardEvents.some((event) => event.type === 'keyup' && event.code === 'KeyQ'));

    click(document, 'newcatroid-touch-settings');
    const action = document.getElementById('newcatroid-touch-action');
    action.value = 'mouse';
    action.dispatchEvent(new FakeEvent('change'));
    const mouse = document.getElementById('newcatroid-touch-mouse');
    mouse.value = '2';
    mouse.dispatchEvent(new FakeEvent('change'));
    click(document, 'newcatroid-touch-done');
    pointer(controlById(document, customId), 'pointerdown', 12, 500, 220);
    pointer(controlById(document, customId), 'pointerup', 12, 500, 220);
    assert.ok(mouseEvents.some((event) => event.type === 'mousedown' && event.button === 2));
    assert.ok(mouseEvents.some((event) => event.type === 'mouseup' && event.button === 2));

    click(document, 'newcatroid-touch-settings');
    click(document, 'newcatroid-touch-remove');
    assert.equal(document.getElementById('newcatroid-control-' + customId), null);
    click(document, 'newcatroid-touch-done');
});

test('layouts persist per game, orientation changes clamp controls, hide/show and restore defaults work', () => {
    const storage = new FakeStorage();
    const first = createBrowser({ storage, origin: 'http://127.0.0.1:8000', pathname: '/game-a/index.html' });
    click(first.document, 'newcatroid-touch-settings');
    const opacity = first.document.getElementById('newcatroid-touch-opacity');
    opacity.value = '96';
    opacity.dispatchEvent(new FakeEvent('input'));
    const jump = controlById(first.document, 'jump');
    pointer(jump, 'pointerdown', 20, 700, 300);
    pointer(jump, 'pointermove', 20, 5000, -5000);
    pointer(jump, 'pointerup', 20, 5000, -5000);
    first.window.innerWidth = 320;
    first.window.innerHeight = 220;
    first.window.dispatchEvent(new FakeEvent('resize'));
    const clamped = layoutFrom(storage, 'http://127.0.0.1:8000', '/game-a/index.html').controls.find((control) => control.id === 'jump');
    assert.ok(clamped.x > 0 && clamped.x < 1);
    assert.ok(clamped.y > 0 && clamped.y < 1);
    click(first.document, 'newcatroid-touch-toggle');
    click(first.document, 'newcatroid-touch-done');
    assert.equal(layoutFrom(storage, 'http://127.0.0.1:8000', '/game-a/index.html').hidden, true);

    const reloaded = createBrowser({ storage, origin: 'http://127.0.0.1:8000', pathname: '/game-a/index.html' });
    assert.equal(layoutFrom(storage, 'http://127.0.0.1:8000', '/game-a/index.html').opacity, 0.96);
    assert.equal(reloaded.document.getElementById('newcatroid-touch-control-layer').style.display, 'none');
    click(reloaded.document, 'newcatroid-touch-settings');
    assert.equal(layoutFrom(storage, 'http://127.0.0.1:8000', '/game-a/index.html').hidden, false, 'opening editor restores controls for editing');

    const otherGame = createBrowser({ storage, origin: 'http://127.0.0.1:8000', pathname: '/game-b/index.html' });
    click(otherGame.document, 'newcatroid-touch-settings');
    assert.equal(layoutFrom(storage, 'http://127.0.0.1:8000', '/game-b/index.html').opacity, 0.55, 'another game starts with independent defaults');
    click(reloaded.document, 'newcatroid-touch-reset');
    const restored = layoutFrom(storage, 'http://127.0.0.1:8000', '/game-a/index.html');
    assert.equal(restored.opacity, 0.55);
    assert.equal(restored.spacing, 0.14);
    assert.equal(restored.controls.filter((control) => control.custom).length, 0);
    assert.ok(otherGame.document.getElementById('newcatroid-touch-settings'));
});

test('desktop pointer devices do not receive the touch-control overlay', () => {
    const { window, document } = createBrowser({ touch: false });
    assert.equal(window.__newcatroidTouchControls, true);
    assert.equal(document.getElementById('newcatroid-touch-controls'), null);
    assert.equal(document.getElementById('newcatroid-touch-settings'), null);
});
