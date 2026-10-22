package net.kgtkr.polytwi.injector

object PushPolyfillInjector {

    const val JS_POLYFILL = """
    (function() {
        if (window.__webPushPolyfilled) return;
        window.__webPushPolyfilled = true;

        console.log("[WebPushPolyfill] Initializing Web Push, Dark Mode & CPFT polyfill");

        // Default X.com VAPID key (base64url)
        const DEFAULT_VAPID_KEY = "BM5oEo0xDUpgylKDTlsd8pZmxQA1leYINiY-rSscWYK_3tWAkz4VMbtf1MLE_Yyd6iII6o-e3Q9TCN5vZMzVMEs";

        // 1. Setup CPFT <script id="cpftSettings"> and Dark Mode Cookie Sync
        function ensureCpftSettingsElement() {
            let el = document.querySelector('script#cpftSettings');
            if (!el) {
                el = document.createElement('script');
                el.id = 'cpftSettings';
                el.type = 'application/json';
                let initialJson = '{}';
                try {
                    if (window.AndroidCpftBridge && window.AndroidCpftBridge.getAllConfig) {
                        initialJson = window.AndroidCpftBridge.getAllConfig() || '{}';
                    }
                } catch (e) {
                    console.error("[CPFT] Failed to read initial config", e);
                }
                el.textContent = initialJson;
                (document.head || document.documentElement).appendChild(el);
            }
            return el;
        }

        function syncDarkModeCookie() {
            try {
                if (!window.AndroidCpftBridge || !window.AndroidCpftBridge.isDarkMode) return;
                const isDark = Boolean(window.AndroidCpftBridge.isDarkMode());
                let darkTheme = 'lightsOut';
                try {
                    const cfgStr = window.AndroidCpftBridge.getAllConfig ? window.AndroidCpftBridge.getAllConfig() : '{}';
                    const cfg = JSON.parse(cfgStr);
                    if (cfg && cfg.darkModeTheme) {
                        darkTheme = cfg.darkModeTheme;
                    }
                } catch (e) {}

                const targetNightMode = isDark ? (darkTheme === 'dim' ? '1' : '2') : '0';
                const currentMatch = document.cookie.match(/(?:^|;\s*)night_mode=([^;]*)/);
                const currentMode = currentMatch ? currentMatch[1] : null;

                if (currentMode !== targetNightMode) {
                    const maxAge = 60 * 60 * 24 * 365;
                    document.cookie = "night_mode=" + targetNightMode + "; domain=.x.com; path=/; max-age=" + maxAge + "; SameSite=None; Secure";
                    document.cookie = "night_mode=" + targetNightMode + "; domain=.twitter.com; path=/; max-age=" + maxAge + "; SameSite=None; Secure";
                    document.cookie = "night_mode=" + targetNightMode + "; path=/; max-age=" + maxAge + "; SameSite=Lax";
                }
            } catch (e) {
                console.error("[DarkModeSync] Error syncing night_mode cookie", e);
            }
        }

        syncDarkModeCookie();
        ensureCpftSettingsElement();

        // Also re-ensure cpftSettings element if documentElement is replaced
        document.addEventListener('DOMContentLoaded', function() {
            ensureCpftSettingsElement();
        });

        // Listen for config changes emitted by CPFT script.js
        window.addEventListener('message', function(event) {
            if (event.source !== window || !event.data) return;
            if (event.data.type === 'cpftConfigChange' && event.data.changes) {
                try {
                    if (window.AndroidCpftBridge && window.AndroidCpftBridge.mergeConfig) {
                        window.AndroidCpftBridge.mergeConfig(JSON.stringify(event.data.changes));
                    }
                } catch (e) {}
            }
        });

        // Helper called from Android native UI when user changes CPFT settings
        window.__applyCpftConfigChanges = function(changesJsonStr) {
            try {
                const el = ensureCpftSettingsElement();
                el.textContent = changesJsonStr;
                syncDarkModeCookie();
            } catch (e) {
                console.error("[CPFT] Failed to apply config changes", e);
            }
        };

        // Patch matchMedia for prefers-color-scheme so X.com always sees system dark mode
        if (window.matchMedia) {
            const origMatchMedia = window.matchMedia.bind(window);
            window.matchMedia = function(query) {
                const mql = origMatchMedia(query);
                if (typeof query === 'string' && query.includes('prefers-color-scheme') && window.AndroidCpftBridge && window.AndroidCpftBridge.isDarkMode) {
                    const isDark = Boolean(window.AndroidCpftBridge.isDarkMode());
                    const wantsDark = query.includes('dark');
                    const wantsLight = query.includes('light');
                    const matched = (wantsDark && isDark) || (wantsLight && !isDark);
                    return {
                        matches: matched,
                        media: query,
                        onchange: mql ? mql.onchange : null,
                        addListener: function(cb) { if (mql && mql.addListener) mql.addListener(cb); },
                        removeListener: function(cb) { if (mql && mql.removeListener) mql.removeListener(cb); },
                        addEventListener: function(t, cb, o) { if (mql && mql.addEventListener) mql.addEventListener(t, cb, o); },
                        removeEventListener: function(t, cb, o) { if (mql && mql.removeEventListener) mql.removeEventListener(t, cb, o); },
                        dispatchEvent: function(ev) { return mql && mql.dispatchEvent ? mql.dispatchEvent(ev) : true; }
                    };
                }
                return mql;
            };
        }

        // GM_* Storage Polyfill for userscript compatibility
        if (typeof window.GM_getValue === 'undefined') {
            window.GM_getValue = function(key, defaultValue) {
                try {
                    if (window.AndroidCpftBridge && window.AndroidCpftBridge.get) {
                        const defaultStr = typeof defaultValue !== 'undefined' ? JSON.stringify(defaultValue) : null;
                        const res = window.AndroidCpftBridge.get(key, defaultStr);
                        return res !== null && typeof res !== 'undefined' ? JSON.parse(res) : defaultValue;
                    }
                    const val = localStorage.getItem('cpft_' + key);
                    return val !== null ? JSON.parse(val) : defaultValue;
                } catch(e) {
                    return defaultValue;
                }
            };
        }
        if (typeof window.GM_setValue === 'undefined') {
            window.GM_setValue = function(key, value) {
                try {
                    const jsonStr = JSON.stringify(value);
                    if (window.AndroidCpftBridge && window.AndroidCpftBridge.set) {
                        window.AndroidCpftBridge.set(key, jsonStr);
                    }
                    localStorage.setItem('cpft_' + key, jsonStr);
                } catch(e) {}
            };
        }
        if (typeof window.GM_deleteValue === 'undefined') {
            window.GM_deleteValue = function(key) {
                try {
                    if (window.AndroidCpftBridge && window.AndroidCpftBridge.delete) {
                        window.AndroidCpftBridge.delete(key);
                    }
                    localStorage.removeItem('cpft_' + key);
                } catch(e) {}
            };
        }
        if (typeof window.GM_registerMenuCommand === 'undefined') {
            window.GM_registerMenuCommand = function(name, fn) {
                console.log("[GM_registerMenuCommand]", name);
            };
        }

        // Base64URL helpers
        function base64UrlEncode(buffer) {
            const bytes = new Uint8Array(buffer);
            let binary = '';
            for (let i = 0; i < bytes.byteLength; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        }

        function base64UrlDecode(str) {
            if (!str) return new Uint8Array(0).buffer;
            let base64 = str.replace(/-/g, '+').replace(/_/g, '/');
            while (base64.length % 4) {
                base64 += '=';
            }
            const raw = atob(base64);
            const output = new Uint8Array(raw.length);
            for (let i = 0; i < raw.length; ++i) {
                output[i] = raw.charCodeAt(i);
            }
            return output.buffer;
        }

        // Check if this account already has an active Push subscription
        function hasActiveNativeSubscription() {
            try {
                if (window.AndroidPushBridge && window.AndroidPushBridge.getExistingSubscription) {
                    const existing = window.AndroidPushBridge.getExistingSubscription();
                    return Boolean(existing);
                }
            } catch (e) {}
            return false;
        }

        // Determine initial permission state:
        // If not yet subscribed, report 'default' (and 'prompt' for Permissions API)
        // so X.com shows the "Turn on push notifications" enablement screen!
        let currentNotificationPermission = hasActiveNativeSubscription() ? 'granted' : 'default';
        const permissionStatusListeners = new Set();

        function notifyPermissionChange() {
            permissionStatusListeners.forEach(function(statusObj) {
                statusObj.state = currentNotificationPermission === 'granted' ? 'granted' : 'prompt';
                if (typeof statusObj.onchange === 'function') {
                    try { statusObj.onchange(new Event('change')); } catch (e) {}
                }
                if (statusObj._listeners && statusObj._listeners['change']) {
                    statusObj._listeners['change'].forEach(function(fn) {
                        try { fn.call(statusObj, new Event('change')); } catch (e) {}
                    });
                }
            });
        }

        // Notification API polyfill
        function MockNotification(title, options) {
            this.title = title;
            this.options = options || {};
            this.onclick = null;
            this.onclose = null;
            this.onerror = null;
            this.onshow = null;
        }
        MockNotification.prototype.close = function() {};
        MockNotification.prototype.addEventListener = function() {};
        MockNotification.prototype.removeEventListener = function() {};
        MockNotification.prototype.dispatchEvent = function() { return true; };

        Object.defineProperty(MockNotification, 'permission', {
            get: function() {
                if (currentNotificationPermission !== 'granted' && hasActiveNativeSubscription()) {
                    currentNotificationPermission = 'granted';
                }
                return currentNotificationPermission;
            },
            enumerable: true,
            configurable: true
        });
        MockNotification.maxActions = 2;
        MockNotification.requestPermission = function(callback) {
            currentNotificationPermission = 'granted';
            notifyPermissionChange();
            const p = Promise.resolve('granted');
            if (typeof callback === 'function') {
                p.then(callback);
            }
            return p;
        };

        window.Notification = MockNotification;

        // navigator.permissions.query polyfill
        function createPermissionStatus(name) {
            const statusObj = {
                name: name,
                state: currentNotificationPermission === 'granted' ? 'granted' : 'prompt',
                onchange: null,
                _listeners: {},
                addEventListener: function(type, listener) {
                    if (!this._listeners[type]) this._listeners[type] = new Set();
                    this._listeners[type].add(listener);
                },
                removeEventListener: function(type, listener) {
                    if (this._listeners[type]) this._listeners[type].delete(listener);
                },
                dispatchEvent: function(ev) {
                    return true;
                }
            };
            permissionStatusListeners.add(statusObj);
            return statusObj;
        }

        if (navigator.permissions && navigator.permissions.query) {
            const originalQuery = navigator.permissions.query.bind(navigator.permissions);
            navigator.permissions.query = function(parameters) {
                if (parameters && (parameters.name === 'notifications' || parameters.name === 'push')) {
                    if (currentNotificationPermission !== 'granted' && hasActiveNativeSubscription()) {
                        currentNotificationPermission = 'granted';
                    }
                    return Promise.resolve(createPermissionStatus(parameters.name));
                }
                return originalQuery(parameters);
            };
        } else {
            navigator.permissions = {
                query: function(parameters) {
                    const name = (parameters && parameters.name) ? parameters.name : 'notifications';
                    return Promise.resolve(createPermissionStatus(name));
                }
            };
        }

        // PushSubscription & PushSubscriptionOptions polyfill
        function PushSubscriptionOptionsMock(appServerKeyBuffer) {
            this._userVisibleOnly = true;
            this._applicationServerKey = appServerKeyBuffer || null;
        }
        Object.defineProperty(PushSubscriptionOptionsMock.prototype, 'userVisibleOnly', {
            get: function() { return this._userVisibleOnly !== undefined ? this._userVisibleOnly : true; },
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(PushSubscriptionOptionsMock.prototype, 'applicationServerKey', {
            get: function() { return this._applicationServerKey !== undefined ? this._applicationServerKey : null; },
            enumerable: true,
            configurable: true
        });
        window.PushSubscriptionOptions = PushSubscriptionOptionsMock;

        function PushSubscriptionMock(subData) {
            this._endpoint = subData.endpoint;
            this._expirationTime = null;
            const keyB64 = subData.appServerKey || DEFAULT_VAPID_KEY;
            this._options = new PushSubscriptionOptionsMock(base64UrlDecode(keyB64));
            this._p256dh = subData.p256dh;
            this._auth = subData.auth;
        }
        Object.defineProperty(PushSubscriptionMock.prototype, 'endpoint', {
            get: function() { return this._endpoint; },
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(PushSubscriptionMock.prototype, 'expirationTime', {
            get: function() { return this._expirationTime; },
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(PushSubscriptionMock.prototype, 'options', {
            get: function() { return this._options; },
            enumerable: true,
            configurable: true
        });
        PushSubscriptionMock.prototype.getKey = function(name) {
            if (name === 'p256dh') {
                return base64UrlDecode(this._p256dh);
            }
            if (name === 'auth') {
                return base64UrlDecode(this._auth);
            }
            return null;
        };
        PushSubscriptionMock.prototype.toJSON = function() {
            return {
                endpoint: this.endpoint,
                expirationTime: null,
                keys: {
                    p256dh: this._p256dh,
                    auth: this._auth
                }
            };
        };
        PushSubscriptionMock.prototype.unsubscribe = function() {
            return new Promise(function(resolve) {
                if (window.AndroidPushBridge && window.AndroidPushBridge.unsubscribe) {
                    window.AndroidPushBridge.unsubscribe();
                }
                currentNotificationPermission = 'default';
                notifyPermissionChange();
                resolve(true);
            });
        };
        window.PushSubscription = PushSubscriptionMock;

        function createSubscriptionObject(subData) {
            if (!subData || !subData.endpoint) return null;
            return new PushSubscriptionMock(subData);
        }

        // PushManager polyfill
        function PushManagerMock() {}
        PushManagerMock.supportedContentEncodings = ['aes128gcm', 'aesgcm'];
        PushManagerMock.prototype.supportedContentEncodings = ['aes128gcm', 'aesgcm'];

        PushManagerMock.prototype.permissionState = function(options) {
            if (currentNotificationPermission !== 'granted' && hasActiveNativeSubscription()) {
                currentNotificationPermission = 'granted';
            }
            return Promise.resolve(currentNotificationPermission === 'granted' ? 'granted' : 'prompt');
        };

        PushManagerMock.prototype.getSubscription = function() {
            return new Promise(function(resolve) {
                if (!window.AndroidPushBridge || !window.AndroidPushBridge.getExistingSubscription) {
                    resolve(null);
                    return;
                }
                try {
                    const existing = window.AndroidPushBridge.getExistingSubscription();
                    if (existing) {
                        const parsed = JSON.parse(existing);
                        currentNotificationPermission = 'granted';
                        resolve(createSubscriptionObject(parsed));
                        return;
                    }
                } catch(e) {
                    console.error("[WebPushPolyfill] getSubscription error", e);
                }
                resolve(null);
            });
        };

        PushManagerMock.prototype.subscribe = function(options) {
            return new Promise(function(resolve, reject) {
                if (!window.AndroidPushBridge || !window.AndroidPushBridge.subscribe) {
                    reject(new Error("AndroidPushBridge not available"));
                    return;
                }
                let keyStr = DEFAULT_VAPID_KEY;
                if (options && options.applicationServerKey) {
                    if (typeof options.applicationServerKey === 'string') {
                        keyStr = options.applicationServerKey;
                    } else if (options.applicationServerKey instanceof ArrayBuffer) {
                        keyStr = base64UrlEncode(options.applicationServerKey);
                    } else if (ArrayBuffer.isView(options.applicationServerKey)) {
                        const view = options.applicationServerKey;
                        const slice = view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength);
                        keyStr = base64UrlEncode(slice);
                    }
                }

                try {
                    const resultJson = window.AndroidPushBridge.subscribe(keyStr);
                    if (resultJson) {
                        const parsed = JSON.parse(resultJson);
                        if (!parsed.appServerKey) parsed.appServerKey = keyStr;
                        currentNotificationPermission = 'granted';
                        notifyPermissionChange();
                        resolve(createSubscriptionObject(parsed));
                    } else {
                        reject(new Error("Subscription failed in native"));
                    }
                } catch(e) {
                    reject(e);
                }
            });
        };

        window.PushManager = PushManagerMock;
        const pushManagerInstance = new PushManagerMock();

        // Ensure ServiceWorkerRegistration & ServiceWorker prototypes exist
        if (typeof window.ServiceWorkerRegistration === 'undefined') {
            window.ServiceWorkerRegistration = function ServiceWorkerRegistration() {};
        }
        if (typeof window.ServiceWorker === 'undefined') {
            window.ServiceWorker = function ServiceWorker() {};
        }

        function triggerShowNotification(title, options) {
            try {
                const body = (options && options.body) ? String(options.body) : "";
                if (window.AndroidPushBridge && window.AndroidPushBridge.showTestNotification) {
                    window.AndroidPushBridge.showTestNotification(String(title || "X"), body);
                }
            } catch (e) {
                console.error("[WebPushPolyfill] triggerShowNotification error", e);
            }
            return Promise.resolve();
        }

        Object.defineProperty(ServiceWorkerRegistration.prototype, 'pushManager', {
            get: function() { return pushManagerInstance; },
            enumerable: true,
            configurable: true
        });
        ServiceWorkerRegistration.prototype.showNotification = function(title, options) {
            return triggerShowNotification(title, options);
        };
        ServiceWorkerRegistration.prototype.getNotifications = function(filter) {
            return Promise.resolve([]);
        };

        function patchRegistration(reg) {
            if (!reg) return reg;
            try {
                if (!reg.pushManager) {
                    Object.defineProperty(reg, 'pushManager', {
                        get: function() { return pushManagerInstance; },
                        enumerable: true,
                        configurable: true
                    });
                }
                reg.showNotification = function(title, options) {
                    return triggerShowNotification(title, options);
                };
                if (typeof reg.getNotifications !== 'function') {
                    reg.getNotifications = function(filter) {
                        return Promise.resolve([]);
                    };
                }
            } catch (e) {}
            return reg;
        }

        // Mock ServiceWorker and ServiceWorkerRegistration instances
        const mockServiceWorker = {
            scriptURL: location.origin + '/sw.js',
            state: 'activated',
            onstatechange: null,
            onerror: null,
            postMessage: function() {},
            addEventListener: function() {},
            removeEventListener: function() {},
            dispatchEvent: function() { return true; }
        };
        try { Object.setPrototypeOf(mockServiceWorker, ServiceWorker.prototype); } catch (e) {}

        const mockRegistration = {
            active: mockServiceWorker,
            installing: null,
            waiting: null,
            scope: location.origin + '/',
            updateViaCache: 'imports',
            pushManager: pushManagerInstance,
            onupdatefound: null,
            showNotification: function(title, options) { return triggerShowNotification(title, options); },
            getNotifications: function() { return Promise.resolve([]); },
            update: function() { return Promise.resolve(mockRegistration); },
            unregister: function() { return Promise.resolve(true); },
            addEventListener: function() {},
            removeEventListener: function() {},
            dispatchEvent: function() { return true; }
        };
        try { Object.setPrototypeOf(mockRegistration, ServiceWorkerRegistration.prototype); } catch (e) {}

        // Ensure navigator.serviceWorker exists
        if (!('serviceWorker' in navigator) || !navigator.serviceWorker) {
            const mockSwContainer = {
                ready: Promise.resolve(mockRegistration),
                controller: mockServiceWorker,
                register: function(scriptURL, options) { return Promise.resolve(mockRegistration); },
                getRegistration: function(clientURL) { return Promise.resolve(mockRegistration); },
                getRegistrations: function() { return Promise.resolve([mockRegistration]); },
                addEventListener: function() {},
                removeEventListener: function() {},
                dispatchEvent: function() { return true; }
            };
            try {
                Object.defineProperty(navigator, 'serviceWorker', {
                    value: mockSwContainer,
                    writable: true,
                    configurable: true,
                    enumerable: true
                });
            } catch (e) {
                navigator.serviceWorker = mockSwContainer;
            }
        } else {
            const swContainer = navigator.serviceWorker;
            const swProto = Object.getPrototypeOf(swContainer);

            const origRegister = swContainer.register ? swContainer.register.bind(swContainer) : null;
            if (origRegister) {
                swContainer.register = function(scriptURL, options) {
                    return origRegister(scriptURL, options).then(function(reg) {
                        return patchRegistration(reg || mockRegistration);
                    }).catch(function() {
                        return mockRegistration;
                    });
                };
            }

            const origGetReg = swContainer.getRegistration ? swContainer.getRegistration.bind(swContainer) : null;
            swContainer.getRegistration = function(clientURL) {
                if (!origGetReg) return Promise.resolve(mockRegistration);
                return origGetReg(clientURL).then(function(reg) {
                    if (reg) return patchRegistration(reg);
                    if (origRegister) {
                        return origRegister('/sw.js').then(function(r) {
                            return patchRegistration(r || mockRegistration);
                        }).catch(function() {
                            return mockRegistration;
                        });
                    }
                    return mockRegistration;
                }).catch(function() {
                    return mockRegistration;
                });
            };

            const origGetRegs = swContainer.getRegistrations ? swContainer.getRegistrations.bind(swContainer) : null;
            swContainer.getRegistrations = function() {
                if (!origGetRegs) return Promise.resolve([mockRegistration]);
                return origGetRegs().then(function(regs) {
                    if (regs && regs.length > 0) {
                        return regs.map(patchRegistration);
                    }
                    return [mockRegistration];
                }).catch(function() {
                    return [mockRegistration];
                });
            };

            const protoReadyDesc = (swProto && Object.getOwnPropertyDescriptor(swProto, 'ready')) ||
                Object.getOwnPropertyDescriptor(swContainer, 'ready');
            Object.defineProperty(swContainer, 'ready', {
                get: function() {
                    if (protoReadyDesc && protoReadyDesc.get) {
                        const nativeReady = protoReadyDesc.get.call(swContainer).then(function(reg) {
                            return patchRegistration(reg || mockRegistration);
                        }).catch(function() {
                            return mockRegistration;
                        });
                        const timeoutFallback = new Promise(function(resolve) {
                            setTimeout(function() { resolve(mockRegistration); }, 1500);
                        });
                        return Promise.race([nativeReady, timeoutFallback]);
                    }
                    return Promise.resolve(mockRegistration);
                },
                configurable: true,
                enumerable: true
            });
        }
    })();
    """
}
