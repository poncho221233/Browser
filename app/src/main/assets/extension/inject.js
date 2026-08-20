/**
 * AntiDetect content-script bridge (GeckoView / Firefox).
 *
 * Content scripts run in an ISOLATED world – prototype patches do NOT
 * affect page JS (CreepJS, FingerprintJS, etc.).
 * We therefore inject the real payload into the PAGE world as an inline
 * <script> at document_start, then remove the node.
 */
(function () {
  "use strict";

  // =====================================================================
  // EARLY Worker / ServiceWorker trap – runs BEFORE config loads.
  // CreepJS captures Worker at page start; if we patch only after async
  // config, workers still leak real Adreno + Firefox UA.
  // =====================================================================
  (function installEarlyWorkerTrap() {
    var trapSrc = "(" + function () {
      if (window.__gestorWorkerTrapV4) return;
      window.__gestorWorkerTrapV4 = true;
      window.__GESTOR_CFG = window.__GESTOR_CFG || null;

      var NativeWorker = typeof Worker !== "undefined" ? Worker : null;
      if (!NativeWorker) return;

      var DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
      var DEFAULT_GPU_V = "Google Inc. (Intel)";
      var DEFAULT_GPU_R = "ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)";

      function cfgNow() {
        var c = window.__GESTOR_CFG;
        try {
          if (!c || !c.userAgent) {
            try { c = JSON.parse(sessionStorage.getItem("__adcfg") || "null") || c; } catch (e) {}
          }
        } catch (e) {}
        return c || {};
      }

      // CreepJS rejects worker scope if !userAgent – never send empty strings
      function creepPayload() {
        var c = cfgNow();
        var ua = (c.userAgent && String(c.userAgent).length > 10) ? c.userAgent : DEFAULT_UA;
        var platform = c.platform || "Win32";
        var lang = c.language || "es-MX";
        var langs = Array.isArray(c.languages) && c.languages.length
          ? c.languages.join(",")
          : "es-MX,es,en-US,en";
        var gpuV = c.webglVendor || c.gpuVendor || DEFAULT_GPU_V;
        var gpuR = c.webglRenderer || c.gpuRenderer || DEFAULT_GPU_R;
        var cores = (c.hardwareConcurrency | 0) || 8;
        var mem = (c.deviceMemory | 0) || 8;
        var tz = c.timezone || "America/Mexico_City";
        return {
          lied: 0,
          lies: { proto: false },
          locale: lang,
          systemCurrencyLocale: "1 US dollar",
          engineCurrencyLocale: "1 US dollar",
          localeEntropyIsTrusty: true,
          localeIntlEntropyIsTrusty: true,
          timezoneOffset: 360,
          timezoneLocation: tz,
          deviceMemory: mem,
          hardwareConcurrency: cores,
          language: lang,
          languages: langs,
          platform: platform,
          userAgent: ua,
          webglRenderer: gpuR,
          webglVendor: gpuV,
          userAgentData: undefined
        };
      }

      // Real Worker with prelude (importScripts) – required for reCAPTCHA / real sites.
      // Fake workers break checkbox → challenge. Spoof only navigator/WebGL inside worker.
      function buildWorkerPrelude() {
        return [
          "(function(){",
          "try{",
          "var C=self.__GESTOR_CFG||{};",
          "try{if(!C.userAgent){C=JSON.parse(self.name||'{}')||C;}catch(e){}",
          "var UA=C.userAgent||\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36\";",
          "var PL=C.platform||'Win32';",
          "var LG=C.language||'es-MX';",
          "var LGS=Array.isArray(C.languages)&&C.languages.length?C.languages:[LG,'es','en-US'];",
          "var HC=(C.hardwareConcurrency|0)||8;",
          "var DM=(C.deviceMemory|0)||8;",
          "var GV=C.webglVendor||C.gpuVendor||'Google Inc. (Intel)';",
          "var GR=C.webglRenderer||C.gpuRenderer||'ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)';",
          "function patchNav(proto){",
          "  if(!proto)return;",
          "  try{Object.defineProperty(proto,'userAgent',{get:function(){return UA},configurable:true});}catch(e){}",
          "  try{Object.defineProperty(proto,'platform',{get:function(){return PL},configurable:true});}catch(e){}",
          "  try{Object.defineProperty(proto,'language',{get:function(){return LG},configurable:true});}catch(e){}",
          "  try{Object.defineProperty(proto,'languages',{get:function(){return LGS.slice()},configurable:true});}catch(e){}",
          "  try{Object.defineProperty(proto,'hardwareConcurrency',{get:function(){return HC},configurable:true});}catch(e){}",
          "  try{Object.defineProperty(proto,'deviceMemory',{get:function(){return DM},configurable:true});}catch(e){}",
          "}",
          "try{patchNav(self.Navigator&&self.Navigator.prototype);}catch(e){}",
          "try{if(typeof WorkerNavigator!=='undefined')patchNav(WorkerNavigator.prototype);}catch(e){}",
          "try{patchNav(Object.getPrototypeOf(self.navigator));}catch(e){}",
          "try{",
          "  var gp=WebGLRenderingContext&&WebGLRenderingContext.prototype;",
          "  if(gp&&gp.getParameter){",
          "    var o=gp.getParameter;",
          "    gp.getParameter=function(p){",
          "      if(p===0x1F00||p===37445)return GV;",
          "      if(p===0x1F01||p===37446)return GR;",
          "      return o.apply(this,arguments);",
          "    };",
          "  }",
          "}catch(e){}",
          "try{",
          "  if(typeof WebGL2RenderingContext!=='undefined'){",
          "    var gp2=WebGL2RenderingContext.prototype;",
          "    if(gp2&&gp2.getParameter){",
          "      var o2=gp2.getParameter;",
          "      gp2.getParameter=function(p){",
          "        if(p===0x1F00||p===37445)return GV;",
          "        if(p===0x1F01||p===37446)return GR;",
          "        return o2.apply(this,arguments);",
          "      };",
          "    }",
          "  }",
          "}catch(e){}",
          "}catch(e){}",
          "})();"
        ].join("\\n");
      }

      function cfgForWorkerName() {
        var c = cfgNow();
        try {
          return JSON.stringify({
            userAgent: c.userAgent || DEFAULT_UA,
            platform: c.platform || "Win32",
            language: c.language || "es-MX",
            languages: Array.isArray(c.languages) ? c.languages : ["es-MX","es","en-US"],
            hardwareConcurrency: (c.hardwareConcurrency|0)||8,
            deviceMemory: (c.deviceMemory|0)||8,
            webglVendor: c.webglVendor || c.gpuVendor || DEFAULT_GPU_V,
            webglRenderer: c.webglRenderer || c.gpuRenderer || DEFAULT_GPU_R
          });
        } catch (e) {
          return "{}";
        }
      }

      function SpoofedWorker(scriptURL, options) {
        var abs;
        try {
          abs = new URL(String(scriptURL), location.href).href;
        } catch (e) {
          abs = String(scriptURL);
        }
        var nameCfg = cfgForWorkerName();
        var code = buildWorkerPrelude() +
          "\\ntry{self.__GESTOR_CFG=JSON.parse(self.name||'{}');}catch(e){}" +
          "\\ntry{importScripts(" + JSON.stringify(abs) + ");}catch(e){try{self.postMessage({__err:String(e)});}catch(x){}}";
        var blob = new Blob([code], { type: "text/javascript" });
        var burl = URL.createObjectURL(blob);
        var opts = options;
        try {
          if (opts && typeof opts === "object") {
            opts = Object.assign({}, opts, { name: nameCfg });
          } else {
            opts = { name: nameCfg };
          }
        } catch (e) {
          opts = { name: nameCfg };
        }
        var w = new NativeWorker(burl, opts);
        try { setTimeout(function () { try { URL.revokeObjectURL(burl); } catch (e) {} }, 60000); } catch (e) {}
        return w;
      }
      SpoofedWorker.prototype = NativeWorker.prototype;
      try { Object.defineProperty(SpoofedWorker, "name", { value: "Worker", configurable: true }); } catch (e) {}
      try {
        SpoofedWorker.toString = function () { return "function Worker() { [native code] }"; };
      } catch (e) {}

      try {
        Object.defineProperty(window, "Worker", {
          configurable: true,
          enumerable: false,
          writable: true,
          value: SpoofedWorker
        });
      } catch (e) {
        try { window.Worker = SpoofedWorker; } catch (e2) {}
      }

      // SharedWorker: keep native when possible (reCAPTCHA rarely needs it)
      // Do NOT replace with a silent stub.

      // ServiceWorker: keep native – rejecting breaks some Google/recaptcha flows

      // MAIN THREAD early GPU / screen / navigator – CreepJS samples before async config
      (function earlyMainSpoof() {
        function apply() {
          var c = cfgNow();
          // Do NOT force Windows/UHD defaults when profile config is not ready yet
          var hasReal = c && ((c.webglRenderer && String(c.webglRenderer).length > 8) ||
                              (c.gpuRenderer && String(c.gpuRenderer).length > 8) ||
                              (c.userAgent && String(c.userAgent).length > 30));
          if (!hasReal) return;
          var UA = c.userAgent;
          var PL = c.platform || "Win32";
          var LG = c.language || "es-MX";
          var LGS = Array.isArray(c.languages) && c.languages.length ? c.languages : ["es-MX","es","en-US","en"];
          var HC = (c.hardwareConcurrency | 0) || 8;
          var DM = (c.deviceMemory | 0) || 8;
          var GV = c.webglVendor || c.gpuVendor || DEFAULT_GPU_V;
          var GR = c.webglRenderer || c.gpuRenderer || DEFAULT_GPU_R;
          var SW = (c.screenWidth | 0) || 0;
          var SH = (c.screenHeight | 0) || 0;
          var os = String(c.os || "").toLowerCase();
          var isDesktop = /win|mac|linux/.test(os) || /Windows NT|Macintosh|Linux x86_64/i.test(UA);
          if (isDesktop && (SW < 1280 || !SW)) { SW = 1920; SH = 1080; }
          if (/mac/.test(os) && (SW < 1280 || !SW)) { SW = 2560; SH = 1600; }
          // Only strip real phone GPU if we claim desktop
          if (isDesktop && /Adreno|Mali/i.test(GR)) {
            /* keep GR only if profile explicitly set non-Adreno; else leave as-is from cfg */
          }
          try {
            var NP = Navigator.prototype;
            function g(p, v) {
              try { Object.defineProperty(NP, p, { get: function () { return v; }, set: function () {}, configurable: true, enumerable: true }); } catch (e) {}
            }
            g("userAgent", UA); g("platform", PL); g("language", LG);
            g("languages", LGS.slice()); g("hardwareConcurrency", HC); g("deviceMemory", DM);
            try { g("webdriver", false); } catch (e) {}
          } catch (e) {}
          try {
            if (SW > 0) {
              function gs(obj, p, v) {
                try { Object.defineProperty(obj, p, { get: function () { return v; }, set: function () {}, configurable: true }); } catch (e) {}
              }
              gs(Screen.prototype, "width", SW);
              gs(Screen.prototype, "height", SH);
              gs(Screen.prototype, "availWidth", SW);
              gs(Screen.prototype, "availHeight", SH - 40);
              gs(window, "innerWidth", Math.min(SW, 1440));
              gs(window, "innerHeight", Math.min(SH, 900));
              gs(window, "outerWidth", Math.min(SW, 1440));
              gs(window, "outerHeight", Math.min(SH, 900));
            }
          } catch (e) {}
          try {
            function wrapGP(orig) {
              return function (p) {
                var n = p | 0;
                if (n === 0x1F00 || n === 37445) return GV;
                if (n === 0x1F01 || n === 37446) return GR;
                return orig.apply(this, arguments);
              };
            }
            if (window.WebGLRenderingContext && WebGLRenderingContext.prototype.getParameter) {
              WebGLRenderingContext.prototype.getParameter = wrapGP(WebGLRenderingContext.prototype.getParameter);
            }
            if (typeof WebGL2RenderingContext !== "undefined" && WebGL2RenderingContext.prototype.getParameter) {
              WebGL2RenderingContext.prototype.getParameter = wrapGP(WebGL2RenderingContext.prototype.getParameter);
            }
          } catch (e) {}
        }
        apply();
        // Re-apply when native pushes config
        var prev = window.__gestorApplyConfig;
        window.__gestorApplyConfig = function (cfg) {
          try { window.__GESTOR_CFG = cfg; } catch (e) {}
          apply();
          if (typeof prev === "function") try { prev(cfg); } catch (e) {}
        };
        var n = 0;
        var iv = setInterval(function () {
          n++;
          apply();
          if (n > 40) clearInterval(iv);
        }, 50);
      })();

    }.toString() + ")();";

    function injectTrap() {
      try {
        var s = document.createElement("script");
        s.textContent = trapSrc;
        var root = document.documentElement || document.head;
        if (root) {
          root.insertBefore(s, root.firstChild);
          s.remove();
          return true;
        }
      } catch (e) {}
      return false;
    }
    if (!injectTrap()) {
      var n = 0;
      var iv = setInterval(function () {
        n++;
        if (injectTrap() || n > 100) clearInterval(iv);
      }, 1);
    }
  })();

  // Neutral defaults – MUST be overridden by per-profile config.
  // Do NOT default to Mac: that caused Android/Windows profiles to look like M3 Max.
  var defaultConfig = {
    noiseSeed: 0xC0FFEE,
    userAgent: "",
    platform: "",
    language: "es-MX",
    languages: ["es-MX", "es", "en-US", "en"],
    os: "",
    screenWidth: 0,
    screenHeight: 0,
    webglVendor: "",
    webglRenderer: "",
    canvasNoise: "AutoNoise",
    webglNoise: "AutoNoise",
    audioNoise: "AutoNoise",
    clientRectsNoise: "AutoNoise",
    fontsNoise: "AutoNoise",
    blockWebRTC: true,
    timezone: "America/Mexico_City",
    geoMode: "Block",
    geoLatitude: 0,
    geoLongitude: 0,
    microphones: 1,
    speakers: 1,
    webcams: 0,
    hardwareConcurrency: 0,
    deviceMemory: 0,
    devicePixelRatio: 0,
    batteryLevel: 0.75,
    batteryCharging: false,
    effectiveType: "4g",
    downlink: 10,
    rtt: 50,
    connectionType: "wifi",
    voices: [],
    fonts: [],
    clientHints: {}
  };

  function injectPageWorld(cfg) {
    var payload = "(" + pageWorldSource.toString() + ")(" + JSON.stringify(cfg) + ");";

    // Persist for page-world.js (extension URL, CSP-safe)
    try { sessionStorage.setItem("__adcfg", JSON.stringify(cfg)); } catch (e) {}
    try { window.__GESTOR_CFG = cfg; } catch (e) {}

    // PRIMARY: load page-world.js from extension (bypasses page CSP that blocks inline scripts)
    function viaExtensionUrl() {
      try {
        if (typeof browser === "undefined" || !browser.runtime || !browser.runtime.getURL) return false;
        var root = document.documentElement || document.head || document.body;
        if (!root) return false;
        var s = document.createElement("script");
        s.src = browser.runtime.getURL("page-world.js");
        s.async = false;
        root.appendChild(s);
        // Notify page world after a tick
        setTimeout(function () {
          try { window.postMessage({ __gestorPageCfg: cfg, __pwCfg: cfg }, "*"); } catch (e) {}
        }, 0);
        return true;
      } catch (e) { return false; }
    }

    function viaScriptTag() {
      var root = document.documentElement || document.head || document.body;
      if (!root) return false;
      var s = document.createElement("script");
      s.textContent = payload;
      root.appendChild(s);
      s.remove();
      return true;
    }

    function viaBlob() {
      try {
        var root = document.documentElement || document.head;
        if (!root) return false;
        var blob = new Blob([payload], { type: "text/javascript" });
        var url = URL.createObjectURL(blob);
        var s = document.createElement("script");
        s.src = url;
        s.onload = function () { try { URL.revokeObjectURL(url); } catch (e) {} };
        root.appendChild(s);
        return true;
      } catch (e) { return false; }
    }

    // Firefox Xray / wrappedJSObject – patch page prototypes directly from content script
    function viaXray(cfg) {
      try {
        if (typeof exportFunction !== "function" || !window.wrappedJSObject) return false;
        var pageWin = window.wrappedJSObject;
        var w = (cfg.screenWidth | 0) || 1920;
        var h = (cfg.screenHeight | 0) || 1080;
        var dpr = (typeof cfg.devicePixelRatio === "number" && cfg.devicePixelRatio > 0) ? cfg.devicePixelRatio : 2;
        var osHint = String(cfg.os || cfg.platform || "").toLowerCase();
        var uaHint = String(cfg.userAgent || "").toLowerCase();
        var isIOS = osHint.indexOf("ios") >= 0 || uaHint.indexOf("iphone") >= 0;
        var isAndroid = osHint.indexOf("android") >= 0 || uaHint.indexOf("android") >= 0;
        var isMac = !isIOS && !isAndroid && (osHint.indexOf("mac") >= 0 || uaHint.indexOf("macintosh") >= 0);
        var vendor = cfg.webglVendor || (isIOS ? "Apple Inc." : isAndroid ? "Qualcomm" : isMac ? "Google Inc. (Apple)" : "Google Inc. (NVIDIA)");
        var renderer = cfg.webglRenderer || (isIOS ? "Apple GPU" : isAndroid ? "Adreno (TM) 750" : isMac
          ? "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Max, Unspecified Version)"
          : "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)");
        var UNMASKED_VENDOR = 37445;
        var UNMASKED_RENDERER = 37446;

        function def(obj, prop, val) {
          var getter = exportFunction(function () { return val; }, pageWin);
          Object.defineProperty(obj, prop, { get: getter, configurable: true, enumerable: true });
        }

        try {
          def(pageWin.Screen.prototype, "width", w);
          def(pageWin.Screen.prototype, "height", h);
          def(pageWin.Screen.prototype, "availWidth", w);
          def(pageWin.Screen.prototype, "availHeight", Math.max(h - 40, 1));
          def(pageWin.Screen.prototype, "colorDepth", 24);
          def(pageWin.Screen.prototype, "pixelDepth", 24);
          if (pageWin.screen) {
            def(pageWin.screen, "width", w);
            def(pageWin.screen, "height", h);
            def(pageWin.screen, "availWidth", w);
            def(pageWin.screen, "availHeight", Math.max(h - 40, 1));
          }
        } catch (e) {}

        try {
          Object.defineProperty(pageWin, "devicePixelRatio", {
            get: exportFunction(function () { return dpr; }, pageWin),
            configurable: true, enumerable: true
          });
        } catch (e) {}

        function wrapGP(orig) {
          return exportFunction(function getParameter(pname) {
            var p = pname | 0;
            if (p === UNMASKED_VENDOR || p === 0x9245) return vendor;
            if (p === UNMASKED_RENDERER || p === 0x9246) return renderer;
            if (p === 0x1F00 || p === 7936) return vendor;   // GL_VENDOR
            if (p === 0x1F01 || p === 7937) return renderer; // GL_RENDERER
            return orig.call(this, pname);
          }, pageWin);
        }

        try {
          if (pageWin.WebGLRenderingContext) {
            var o1 = pageWin.WebGLRenderingContext.prototype.getParameter;
            pageWin.WebGLRenderingContext.prototype.getParameter = wrapGP(o1);
          }
          if (pageWin.WebGL2RenderingContext) {
            var o2 = pageWin.WebGL2RenderingContext.prototype.getParameter;
            pageWin.WebGL2RenderingContext.prototype.getParameter = wrapGP(o2);
          }
        } catch (e) {}

        // Navigator basics
        try {
          if (cfg.userAgent) def(pageWin.Navigator.prototype, "userAgent", cfg.userAgent);
          if (cfg.platform) def(pageWin.Navigator.prototype, "platform", cfg.platform);
          if (cfg.hardwareConcurrency) def(pageWin.Navigator.prototype, "hardwareConcurrency", cfg.hardwareConcurrency | 0);
        } catch (e) {}

        return true;
      } catch (e) {
        return false;
      }
    }

    // Try all strategies – extension URL first (CSP-safe on CreepJS / Google)
    var ok = false;
    try { ok = viaExtensionUrl() || ok; } catch (e) {}
    try { ok = viaXray(cfg) || ok; } catch (e) {}
    try { ok = viaScriptTag() || ok; } catch (e) {}
    try { ok = viaBlob() || ok; } catch (e) {}

    // If documentElement not ready yet, retry soon
    if (!document.documentElement) {
      var tries = 0;
      var iv = setInterval(function () {
        tries++;
        if (document.documentElement || tries > 50) {
          clearInterval(iv);
          try { viaScriptTag(); } catch (e) {}
          try { viaXray(cfg); } catch (e) {}
        }
      }, 10);
    }
  }

  // =====================================================================
  // PAGE-WORLD PAYLOAD (executed in the real page JS realm)
  // =====================================================================
  function pageWorldSource(config) {
    "use strict";
    config = config || {};
    // Always publish config for early Worker trap
    try { window.__GESTOR_CFG = config; } catch (e) {}
    try { sessionStorage.setItem("__adcfg", JSON.stringify(config)); } catch (e) {}
    // Re-apply allowed when profile config changes (compare seed+gpu+screen)
    try {
      var sig = String(config.noiseSeed) + "|" + String(config.webglRenderer) + "|" + String(config.screenWidth);
      if (window.__antiDetectSig === sig) return;
      window.__antiDetectSig = sig;
    } catch (e) {}
    window.__antiDetectPageApplied = true;

    var seed = (Number(config.noiseSeed) || 0xC0FFEE) >>> 0;

    // ---- natives ----
    var Native = {
      FunctionToString: Function.prototype.toString,
      defineProperty: Object.defineProperty,
      getOwnPropertyDescriptor: Object.getOwnPropertyDescriptor,
      ReflectApply: Reflect.apply
    };

    // ---- toString protection ----
    var protectedFns = new WeakMap();
    function makeNativeString(name) {
      if (!name) return "function () { [native code] }";
      return "function " + name + "() { [native code] }";
    }
    function protect(fn, name) {
      if (typeof fn !== "function") return fn;
      if (!protectedFns.has(fn)) protectedFns.set(fn, makeNativeString(name || fn.name || ""));
      return fn;
    }
    var patchedToString = function toString() {
      if (protectedFns.has(this)) return protectedFns.get(this);
      return Native.ReflectApply(Native.FunctionToString, this, arguments);
    };
    protectedFns.set(patchedToString, makeNativeString("toString"));
    try {
      Native.defineProperty(Function.prototype, "toString", {
        value: patchedToString, writable: true, enumerable: false, configurable: true
      });
    } catch (e) {
      Function.prototype.toString = patchedToString;
    }

    // ---- Mulberry32 ----
    function mulberry32(a) {
      a = a >>> 0;
      return function () {
        a |= 0;
        a = (a + 0x6D2B79F5) | 0;
        var t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
      };
    }
    var prng = mulberry32(seed);

    function defineGetter(obj, prop, getter, name) {
      var g = protect(getter, name || ("get " + prop));
      try {
        Native.defineProperty(obj, prop, {
          get: g, set: function () {}, configurable: true, enumerable: true
        });
        return true;
      } catch (e) {
        return false;
      }
    }

    // ================================================================
    // SCREEN – prototype + instance + window metrics
    // ================================================================
    (function overrideScreen() {
      var w = (config.screenWidth | 0) || 1920;
      var h = (config.screenHeight | 0) || 1080;
      var dpr = (typeof config.devicePixelRatio === "number" && config.devicePixelRatio > 0)
        ? config.devicePixelRatio : 1;
      var os = String(config.os || config.platform || "").toLowerCase();
      var chromeH = 0;
      if (os.indexOf("win") >= 0) chromeH = 48;
      else if (os.indexOf("mac") >= 0) chromeH = 25;
      else if (os.indexOf("android") >= 0 || os.indexOf("linux") >= 0) chromeH = 0;
      else chromeH = 28;
      var availH = Math.max(h - chromeH, 1);
      var depth = 24;

      function installOn(target) {
        if (!target) return;
        defineGetter(target, "width", function () { return w; }, "get width");
        defineGetter(target, "height", function () { return h; }, "get height");
        defineGetter(target, "availWidth", function () { return w; }, "get availWidth");
        defineGetter(target, "availHeight", function () { return availH; }, "get availHeight");
        defineGetter(target, "colorDepth", function () { return depth; }, "get colorDepth");
        defineGetter(target, "pixelDepth", function () { return depth; }, "get pixelDepth");
        defineGetter(target, "availLeft", function () { return 0; }, "get availLeft");
        defineGetter(target, "availTop", function () { return 0; }, "get availTop");
      }

      try { installOn(Screen.prototype); } catch (e) {}
      try { installOn(window.screen); } catch (e) {}
      // Re-bind window.screen if needed
      try {
        var fakeScreen = window.screen;
        installOn(fakeScreen);
      } catch (e) {}

      try {
        Native.defineProperty(window, "devicePixelRatio", {
          get: protect(function () { return dpr; }, "get devicePixelRatio"),
          set: function () {}, configurable: true, enumerable: true
        });
      } catch (e) {}
      try {
        Native.defineProperty(window, "outerWidth", {
          get: protect(function () { return w; }, "get outerWidth"),
          set: function () {}, configurable: true, enumerable: true
        });
        Native.defineProperty(window, "outerHeight", {
          get: protect(function () { return h; }, "get outerHeight"),
          set: function () {}, configurable: true, enumerable: true
        });
        var innerW = Math.max(w - 0, 1);
        var innerH = Math.max(h - chromeH - 80, 100);
        Native.defineProperty(window, "innerWidth", {
          get: protect(function () { return innerW; }, "get innerWidth"),
          set: function () {}, configurable: true, enumerable: true
        });
        Native.defineProperty(window, "innerHeight", {
          get: protect(function () { return innerH; }, "get innerHeight"),
          set: function () {}, configurable: true, enumerable: true
        });
      } catch (e) {}

      // VisualViewport if present
      try {
        if (window.visualViewport) {
          defineGetter(window.visualViewport, "width", function () { return innerW; });
          defineGetter(window.visualViewport, "height", function () { return innerH; });
          defineGetter(window.visualViewport, "scale", function () { return 1; });
        }
      } catch (e) {}
    })();

    // ================================================================
    // NAVIGATOR
    // ================================================================
    (function overrideNavigator() {
      var nav = Navigator.prototype;
      if (config.userAgent) defineGetter(nav, "userAgent", function () { return config.userAgent; });
      if (config.platform) defineGetter(nav, "platform", function () { return config.platform; });
      if (config.language) defineGetter(nav, "language", function () { return config.language; });
      defineGetter(nav, "languages", function () {
        var langs = Array.isArray(config.languages) ? config.languages.slice() : [config.language || "en-US"];
        return Object.freeze(langs);
      });
      defineGetter(nav, "hardwareConcurrency", function () {
        return (config.hardwareConcurrency | 0) || 8;
      });
      defineGetter(nav, "deviceMemory", function () {
        return (config.deviceMemory | 0) || 8;
      });
      var isMobileOs = (function () {
        var os = String(config.os || config.platform || "").toLowerCase();
        var ua = String(config.userAgent || "").toLowerCase();
        return os.indexOf("android") >= 0 || os.indexOf("iphone") >= 0 || os.indexOf("ipad") >= 0 ||
          os.indexOf("ios") >= 0 || ua.indexOf("android") >= 0 || ua.indexOf("iphone") >= 0;
      })();
      defineGetter(nav, "maxTouchPoints", function () {
        return isMobileOs ? 5 : 0;
      });
      // Desktop must not look like a touch device (CreepJS / headless signals)
      if (!isMobileOs) {
        try {
          delete window.ontouchstart;
          delete window.ontouchmove;
          delete window.ontouchend;
          delete window.ontouchcancel;
          if ("ontouchstart" in window) {
            try { Object.defineProperty(window, "ontouchstart", { get: function () { return undefined; }, configurable: true }); } catch (e) {}
          }
        } catch (e) {}
        try {
          if (window.TouchEvent) {
            // leave constructor but maxTouchPoints=0 is the main signal
          }
        } catch (e) {}
      }
      // appVersion derived from UA
      if (config.userAgent) {
        defineGetter(nav, "appVersion", function () {
          var ua = config.userAgent;
          var i = ua.indexOf("/");
          return i >= 0 ? ua.substring(i + 1) : ua;
        });
      }
      try {
        defineGetter(nav, "vendor", function () {
          var ua = String(config.userAgent || "");
          if (/Firefox/i.test(ua)) return "";
          return "Google Inc.";
        });
      } catch (e) {}
    })();

    // ================================================================
    // Fonts – hide Android system fonts on desktop profiles
    // CreepJS measures via canvas measureText + offsetWidth; patch both paths.
    // ================================================================
    (function overrideFonts() {
      try {
        var os = String(config.os || "").toLowerCase();
        var ua = String(config.userAgent || "").toLowerCase();
        var mobile = os.indexOf("android") >= 0 || os.indexOf("ios") >= 0 ||
          ua.indexOf("android") >= 0 || ua.indexOf("iphone") >= 0;
        if (mobile) return;

        var ANDROID_RE = /roboto|droid\s*sans|droid\s*serif|droid\s*sans\s*mono|noto\s*sans|noto\s*serif|noto\s*color\s*emoji|coming\s*soon|carrois|cutive\s*mono/i;
        var FALLBACK = "Arial";

        function scrubFont(fontStr) {
          var s = String(fontStr || "");
          if (!ANDROID_RE.test(s)) return s;
          return s.replace(ANDROID_RE, FALLBACK);
        }

        // document.fonts.check → deny Android fonts
        if (document.fonts && document.fonts.check) {
          var origCheck = document.fonts.check.bind(document.fonts);
          document.fonts.check = protect(function check(font, text) {
            try {
              if (ANDROID_RE.test(String(font || ""))) return false;
            } catch (e) {}
            return origCheck(font, text);
          }, "check");
        }

        // Canvas: when measuring or drawing with Android font, swap to Arial
        try {
          var proto = CanvasRenderingContext2D.prototype;
          var desc = Object.getOwnPropertyDescriptor(proto, "font");
          if (desc && desc.set) {
            var rawSet = desc.set;
            var rawGet = desc.get;
            Object.defineProperty(proto, "font", {
              configurable: true,
              enumerable: true,
              get: function () { return rawGet.call(this); },
              set: function (v) { rawSet.call(this, scrubFont(v)); }
            });
          }
        } catch (e) {}

        try {
          var origMT = CanvasRenderingContext2D.prototype.measureText;
          CanvasRenderingContext2D.prototype.measureText = protect(function measureText(text) {
            var prev = null;
            try {
              if (ANDROID_RE.test(String(this.font || ""))) {
                prev = this.font;
                this.font = scrubFont(this.font);
              }
            } catch (e) {}
            var m = Native.ReflectApply(origMT, this, arguments);
            try { if (prev != null) this.font = prev; } catch (e) {}
            return m;
          }, "measureText");
        } catch (e) {}
      } catch (e) {}
    })();

    // ================================================================
    // WEBGL / WEBGL2 – UNMASKED_VENDOR / UNMASKED_RENDERER
    // ================================================================
    (function overrideWebGL() {
      var UNMASKED_VENDOR_WEBGL = 37445;   // 0x9245
      var UNMASKED_RENDERER_WEBGL = 37446; // 0x9246
      var osHint = String(config.os || config.platform || "").toLowerCase();
      var uaHint = String(config.userAgent || "").toLowerCase();
      var isIOS = osHint.indexOf("ios") >= 0 || uaHint.indexOf("iphone") >= 0;
      var isAndroid = osHint.indexOf("android") >= 0 || uaHint.indexOf("android") >= 0;
      var isMac = !isIOS && !isAndroid && (osHint.indexOf("mac") >= 0 || uaHint.indexOf("macintosh") >= 0);
      var isLinux = !isAndroid && (osHint.indexOf("linux") >= 0 || uaHint.indexOf("x11") >= 0);
      // OS-specific fallback ONLY when config omitted GPU – never a single global RTX 4080
      var defaultVendor = isIOS ? "Apple Inc."
        : isAndroid ? "Qualcomm"
        : isMac ? "Google Inc. (Apple)"
        : isLinux ? "Intel"
        : "Google Inc. (NVIDIA)";
      var defaultRenderer = isIOS ? "Apple GPU"
        : isAndroid ? "Adreno (TM) 750"
        : isMac ? "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Max, Unspecified Version)"
        : isLinux ? "Mesa Intel(R) UHD Graphics 620 (KBL GT2)"
        : "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)";
      var vendor = config.webglVendor || config.gpuVendor || defaultVendor;
      var renderer = config.webglRenderer || config.gpuRenderer || defaultRenderer;

      // Also spoof plain VENDOR / RENDERER (BrowserLeaks shows these as "Vendor"/"Renderer")
      var GL_VENDOR = 0x1F00;   // 7936
      var GL_RENDERER = 0x1F01; // 7937
      // For standard VENDOR/RENDERER, Firefox/Gecko often reports real GPU (Adreno).
      // Desktop profiles should never leak mobile GPU strings here.
      var plainVendor = vendor;
      var plainRenderer = renderer;
      // Strip ANGLE wrapper for the "plain" fields when it looks better
      try {
        if (/ANGLE\s*\(/i.test(renderer)) {
          // Keep full string for UNMASKED; plain can stay the same (Chrome-like)
          plainRenderer = renderer;
        }
      } catch (e) {}

      function wrapGetParameter(original) {
        if (typeof original !== "function") return original;
        var wrapped = function getParameter(pname) {
          var p = pname | 0;
          if (p === UNMASKED_VENDOR_WEBGL || p === 0x9245) return vendor;
          if (p === UNMASKED_RENDERER_WEBGL || p === 0x9246) return renderer;
          if (p === GL_VENDOR || p === 0x1F00) return plainVendor;
          if (p === GL_RENDERER || p === 0x1F01) return plainRenderer;
          try {
            return Native.ReflectApply(original, this, arguments);
          } catch (e) {
            return null;
          }
        };
        return protect(wrapped, "getParameter");
      }

      function wrapGetExtension(original) {
        if (typeof original !== "function") return original;
        var wrapped = function getExtension(name) {
          var ext = Native.ReflectApply(original, this, arguments);
          if (!ext) return ext;
          // Some engines expose UNMASKED_* only through the extension object constants
          try {
            if (name === "WEBGL_debug_renderer_info" || name === "WEBGL_debug_shaders") {
              // ensure constants exist
              if (typeof ext.UNMASKED_VENDOR_WEBGL === "undefined") {
                try { ext.UNMASKED_VENDOR_WEBGL = UNMASKED_VENDOR_WEBGL; } catch (e) {}
              }
              if (typeof ext.UNMASKED_RENDERER_WEBGL === "undefined") {
                try { ext.UNMASKED_RENDERER_WEBGL = UNMASKED_RENDERER_WEBGL; } catch (e) {}
              }
            }
          } catch (e) {}
          return ext;
        };
        return protect(wrapped, "getExtension");
      }

      function patchContext(Proto) {
        if (!Proto) return;
        try {
          if (Proto.getParameter) Proto.getParameter = wrapGetParameter(Proto.getParameter);
          if (Proto.getExtension) Proto.getExtension = wrapGetExtension(Proto.getExtension);
        } catch (e) {}
      }

      try { patchContext(typeof WebGLRenderingContext !== "undefined" ? WebGLRenderingContext.prototype : null); } catch (e) {}
      try { patchContext(typeof WebGL2RenderingContext !== "undefined" ? WebGL2RenderingContext.prototype : null); } catch (e) {}

      // Also patch existing canvas getContext factory so newly created contexts inherit
      try {
        var origGetContext = HTMLCanvasElement.prototype.getContext;
        HTMLCanvasElement.prototype.getContext = protect(function getContext() {
          var ctx = Native.ReflectApply(origGetContext, this, arguments);
          if (ctx && typeof ctx.getParameter === "function") {
            // Re-wrap instance in case prototype was replaced by engine
            try {
              var p = ctx.getParameter.bind(ctx);
              // only wrap once
              if (!ctx.__adPatched) {
                ctx.getParameter = wrapGetParameter(p);
                if (typeof ctx.getExtension === "function") {
                  ctx.getExtension = wrapGetExtension(ctx.getExtension.bind(ctx));
                }
                try { Native.defineProperty(ctx, "__adPatched", { value: true, enumerable: false }); } catch (e2) {
                  ctx.__adPatched = true;
                }
              }
            } catch (e) {}
          }
          return ctx;
        }, "getContext");
      } catch (e) {}
    })();

    // ================================================================
    // Canvas noise (deterministic)
    // ================================================================
    (function overrideCanvas() {
      var mode = String(config.canvasNoise || "AutoNoise");
      if (mode === "Real") return;

      function noisePixel(arr) {
        if (!arr || !arr.length) return arr;
        // Very subtle: flip LSB on sparse pixels only (harder to flag as "16% rgba noise")
        var local = mulberry32((seed ^ 0xCAFE) >>> 0);
        var step = Math.max(32, (arr.length / 8) | 0);
        for (var i = 0; i < arr.length; i += step * 4) {
          if (i + 2 < arr.length && local() > 0.5) {
            arr[i] = arr[i] ^ 1;
          }
        }
        return arr;
      }

      try {
        var origGID = CanvasRenderingContext2D.prototype.getImageData;
        CanvasRenderingContext2D.prototype.getImageData = protect(function getImageData() {
          if (mode === "Disabled" || mode === "Blocked") {
            var w = arguments[2] | 0, h = arguments[3] | 0;
            return new ImageData(Math.max(w, 1), Math.max(h, 1));
          }
          var data = Native.ReflectApply(origGID, this, arguments);
          try { noisePixel(data.data); } catch (e) {}
          return data;
        }, "getImageData");
      } catch (e) {}

      try {
        var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
        HTMLCanvasElement.prototype.toDataURL = protect(function toDataURL() {
          if (mode === "Disabled" || mode === "Blocked") {
            return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
          }
          try {
            var ctx = origGetContext.call(this, "2d");
            if (ctx) {
              var w = this.width | 0, h = this.height | 0;
              if (w > 0 && h > 0) {
                var img = ctx.getImageData(0, 0, Math.min(w, 16), Math.min(h, 16));
                noisePixel(img.data);
                ctx.putImageData(img, 0, 0);
              }
            }
          } catch (e) {}
          return Native.ReflectApply(origToDataURL, this, arguments);
        }, "toDataURL");
      } catch (e) {}

      var origGetContext = HTMLCanvasElement.prototype.getContext;
    })();

    // ================================================================
    // Audio noise – deterministic from noiseSeed (stable per profile)
    // ================================================================
    (function overrideAudio() {
      var mode = String(config.audioNoise || "AutoNoise");
      if (mode === "Real") return;
      var local = mulberry32((seed ^ 0xA11D) >>> 0);
      function noiseBuffer(buf) {
        if (!buf || !buf.length) return buf;
        // Tiny deterministic perturbation – same seed ⇒ same fingerprint every load
        for (var i = 0; i < buf.length; i += Math.max(1, (buf.length / 128) | 0)) {
          buf[i] = buf[i] + (local() - 0.5) * 0.0001;
        }
        return buf;
      }
      try {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (AC && AC.prototype) {
          var origCreateAnalyser = AC.prototype.createAnalyser;
          if (origCreateAnalyser) {
            AC.prototype.createAnalyser = protect(function createAnalyser() {
              var node = Native.ReflectApply(origCreateAnalyser, this, arguments);
              try {
                var origGFB = node.getFloatFrequencyData;
                node.getFloatFrequencyData = protect(function getFloatFrequencyData(arr) {
                  Native.ReflectApply(origGFB, this, arguments);
                  if (mode !== "Disabled" && arr) noiseBuffer(arr);
                }, "getFloatFrequencyData");
                var origGBB = node.getByteFrequencyData;
                if (origGBB) {
                  node.getByteFrequencyData = protect(function getByteFrequencyData(arr) {
                    Native.ReflectApply(origGBB, this, arguments);
                    if (mode !== "Disabled" && arr) {
                      for (var i = 0; i < arr.length; i += 8) {
                        arr[i] = (arr[i] + ((local() * 2) | 0)) & 255;
                      }
                    }
                  }, "getByteFrequencyData");
                }
              } catch (e) {}
              return node;
            }, "createAnalyser");
          }
        }
      } catch (e) {}
      try {
        if (typeof OfflineAudioContext !== "undefined") {
          var proto = OfflineAudioContext.prototype;
          var origStart = proto.startRendering;
          if (origStart) {
            proto.startRendering = protect(function startRendering() {
              var p = Native.ReflectApply(origStart, this, arguments);
              if (!p || !p.then) return p;
              return p.then(function (buffer) {
                try {
                  if (mode !== "Disabled" && buffer && buffer.numberOfChannels) {
                    for (var c = 0; c < buffer.numberOfChannels; c++) {
                      noiseBuffer(buffer.getChannelData(c));
                    }
                  }
                } catch (e) {}
                return buffer;
              });
            }, "startRendering");
          }
        }
      } catch (e) {}
    })();

    // ================================================================
    // ClientRects / measureText micro-noise
    // ================================================================
    (function overrideRects() {
      var mode = String(config.clientRectsNoise || "AutoNoise");
      if (mode === "Real") return;
      var local = mulberry32((seed ^ 0xBEEF) >>> 0);
      function shift() {
        return (local() - 0.5) * 0.02; // sub-pixel
      }
      try {
        var origGBCR = Element.prototype.getBoundingClientRect;
        Element.prototype.getBoundingClientRect = protect(function getBoundingClientRect() {
          var r = Native.ReflectApply(origGBCR, this, arguments);
          if (mode === "Disabled") return r;
          var dx = shift(), dy = shift();
          return new DOMRect(r.x + dx, r.y + dy, r.width + dx, r.height + dy);
        }, "getBoundingClientRect");
      } catch (e) {}
      try {
        var origMT = CanvasRenderingContext2D.prototype.measureText;
        CanvasRenderingContext2D.prototype.measureText = protect(function measureText(text) {
          var m = Native.ReflectApply(origMT, this, arguments);
          if (mode === "Disabled") return m;
          try {
            var w = m.width + shift();
            return {
              width: w,
              actualBoundingBoxLeft: m.actualBoundingBoxLeft,
              actualBoundingBoxRight: m.actualBoundingBoxRight,
              actualBoundingBoxAscent: m.actualBoundingBoxAscent,
              actualBoundingBoxDescent: m.actualBoundingBoxDescent,
              fontBoundingBoxAscent: m.fontBoundingBoxAscent,
              fontBoundingBoxDescent: m.fontBoundingBoxDescent,
              alphabeticBaseline: m.alphabeticBaseline,
              hangingBaseline: m.hangingBaseline,
              ideographicBaseline: m.ideographicBaseline,
              emHeightAscent: m.emHeightAscent,
              emHeightDescent: m.emHeightDescent
            };
          } catch (e) { return m; }
        }, "measureText");
      } catch (e) {}
    })();

    // ================================================================
    // WebRTC SOFT – keep API (reCAPTCHA/Google), drop host/LAN only.
    // Hard-kill of RTCPeerConnection is a strong bot signal and breaks captchas.
    // ================================================================
    (function softWebRTC() {
      function wrapPC(Orig, name) {
        if (!Orig || Orig.__softRtc) return;
        var Soft = protect(function RTCPeerConnection(cfg, opts) {
          var pc = new Orig(cfg, opts);
          try {
            var _on = null;
            Object.defineProperty(pc, "onicecandidate", {
              get: function () { return _on; },
              set: function (fn) {
                _on = typeof fn === "function"
                  ? function (ev) {
                      if (ev && ev.candidate && ev.candidate.candidate) {
                        var c = ev.candidate.candidate;
                        if (/ typ host /.test(c) ||
                            /192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\./.test(c)) {
                          return;
                        }
                      }
                      fn.call(pc, ev);
                    }
                  : fn;
              },
              configurable: true
            });
            var origAdd = pc.addEventListener.bind(pc);
            pc.addEventListener = function (type, fn, opt) {
              if (type === "icecandidate" && typeof fn === "function") {
                return origAdd(type, function (ev) {
                  if (ev && ev.candidate && ev.candidate.candidate) {
                    var c = ev.candidate.candidate;
                    if (/ typ host /.test(c) ||
                        /192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\./.test(c)) {
                      return;
                    }
                  }
                  return fn.call(pc, ev);
                }, opt);
              }
              return origAdd(type, fn, opt);
            };
          } catch (e) {}
          return pc;
        }, name || "RTCPeerConnection");
        Soft.prototype = Orig.prototype;
        try { Soft.__softRtc = 1; } catch (e) {}
        try {
          Object.defineProperty(window, name, {
            value: Soft,
            writable: true,
            configurable: true
          });
        } catch (e) {
          try { window[name] = Soft; } catch (e2) {}
        }
      }
      try {
        if (window.RTCPeerConnection) wrapPC(window.RTCPeerConnection, "RTCPeerConnection");
        if (window.webkitRTCPeerConnection) wrapPC(window.webkitRTCPeerConnection, "webkitRTCPeerConnection");
        if (window.mozRTCPeerConnection) wrapPC(window.mozRTCPeerConnection, "mozRTCPeerConnection");
      } catch (e) {}
    })();

    // ================================================================
    // Timezone (prefer America/Mexico_City for typical MX IPs)
    // ================================================================
    (function overrideTimezone() {
      var tz = config.timezone || "America/Mexico_City";
      // Standard offsets in minutes (getTimezoneOffset returns minutes *behind* UTC)
      var FALLBACK = {
        "America/Mexico_City": 360,   // UTC-6
        "America/Merida": 360,
        "America/Monterrey": 360,
        "America/New_York": 300,      // UTC-5
        "America/Chicago": 360,
        "America/Denver": 420,
        "America/Los_Angeles": 480,   // UTC-8
        "America/Phoenix": 420,
        "Europe/London": 0,
        "Europe/Madrid": -60,
        "Europe/Berlin": -60,
        "Europe/Paris": -60,
        "Asia/Tokyo": -540,
        "Asia/Shanghai": -480,
        "UTC": 0
      };
      var offsetMin = FALLBACK[tz];
      if (typeof offsetMin !== "number") offsetMin = 360; // default Mexico

      try {
        var origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
        Intl.DateTimeFormat.prototype.resolvedOptions = protect(function resolvedOptions() {
          var opts = Native.ReflectApply(origResolved, this, arguments);
          try { opts.timeZone = tz; } catch (e) {}
          return opts;
        }, "resolvedOptions");
      } catch (e) {}

      try {
        Date.prototype.getTimezoneOffset = protect(function getTimezoneOffset() {
          return offsetMin;
        }, "getTimezoneOffset");
      } catch (e) {}

      // Also patch toLocaleString / toString family that expose zone
      try {
        var origToLocaleString = Date.prototype.toLocaleString;
        Date.prototype.toLocaleString = protect(function toLocaleString() {
          var args = Array.prototype.slice.call(arguments);
          if (args.length === 0) args = [undefined, { timeZone: tz }];
          else if (args.length === 1) args.push({ timeZone: tz });
          else if (args[1] && typeof args[1] === "object") {
            args[1] = Object.assign({}, args[1], { timeZone: tz });
          }
          return Native.ReflectApply(origToLocaleString, this, args);
        }, "toLocaleString");
      } catch (e) {}
    })();

    // ================================================================
    // Battery / Network
    // ================================================================
    (function overrideBatteryNetwork() {
      try {
        if (navigator.getBattery) {
          navigator.getBattery = protect(function getBattery() {
            return Promise.resolve({
              charging: !!config.batteryCharging,
              chargingTime: config.batteryCharging ? 0 : Infinity,
              dischargingTime: config.batteryCharging ? Infinity : 7200,
              level: typeof config.batteryLevel === "number" ? config.batteryLevel : 0.75,
              addEventListener: function () {},
              removeEventListener: function () {},
              dispatchEvent: function () { return true; }
            });
          }, "getBattery");
        }
      } catch (e) {}
      try {
        if (navigator.connection || navigator.mozConnection || navigator.webkitConnection) {
          var conn = {
            effectiveType: config.effectiveType || "4g",
            downlink: typeof config.downlink === "number" ? config.downlink : 10,
            rtt: typeof config.rtt === "number" ? config.rtt : 50,
            saveData: false,
            type: config.connectionType || "wifi",
            addEventListener: function () {},
            removeEventListener: function () {}
          };
          defineGetter(Navigator.prototype, "connection", function () { return conn; });
        }
      } catch (e) {}
    })();

    // ================================================================
    // Locale (force es-MX consistency for Intl)
    // ================================================================
    (function overrideLocale() {
      var lang = config.language || "es-MX";
      var langs = Array.isArray(config.languages) && config.languages.length
        ? config.languages.slice()
        : [lang, "es", "en-US", "en"];
      try {
        defineGetter(Navigator.prototype, "language", function () { return lang; });
        defineGetter(Navigator.prototype, "languages", function () {
          return Object.freeze(langs.slice());
        });
      } catch (e) {}
      try {
        var origDTF = Intl.DateTimeFormat;
        Intl.DateTimeFormat = protect(function DateTimeFormat() {
          var args = Array.prototype.slice.call(arguments);
          if (!args[0]) args[0] = lang;
          return new (Function.prototype.bind.apply(origDTF, [null].concat(args)))();
        }, "DateTimeFormat");
        Intl.DateTimeFormat.prototype = origDTF.prototype;
        Intl.DateTimeFormat.supportedLocalesOf = origDTF.supportedLocalesOf.bind(origDTF);
      } catch (e) {}
      try {
        var origNF = Intl.NumberFormat;
        Intl.NumberFormat = protect(function NumberFormat() {
          var args = Array.prototype.slice.call(arguments);
          if (!args[0]) args[0] = lang;
          return new (Function.prototype.bind.apply(origNF, [null].concat(args)))();
        }, "NumberFormat");
        Intl.NumberFormat.prototype = origNF.prototype;
        Intl.NumberFormat.supportedLocalesOf = origNF.supportedLocalesOf.bind(origNF);
      } catch (e) {}
    })();

    // ================================================================
    // Workers – early trap owns window.Worker (message rewrite + prelude).
    // Only refresh __GESTOR_CFG so rewrite uses latest profile values.
    // ================================================================
    (function syncWorkerConfig() {
      try { window.__GESTOR_CFG = config; } catch (e) {}
      try { sessionStorage.setItem("__adcfg", JSON.stringify(config)); } catch (e) {}
    })();

  }


  // =====================================================================
  // Bridge: load per-profile config then inject into PAGE world
  // =====================================================================
  function go(cfg) {
    var merged = {};
    for (var k in defaultConfig) {
      if (Object.prototype.hasOwnProperty.call(defaultConfig, k)) merged[k] = defaultConfig[k];
    }
    if (cfg && typeof cfg === "object") {
      for (var k2 in cfg) {
        if (Object.prototype.hasOwnProperty.call(cfg, k2) && cfg[k2] != null) {
          merged[k2] = cfg[k2];
        }
      }
      if (cfg.gpuVendor) merged.webglVendor = cfg.gpuVendor;
      if (cfg.gpuRenderer) merged.webglRenderer = cfg.gpuRenderer;
      if (cfg.webglVendor) merged.webglVendor = cfg.webglVendor;
      if (cfg.webglRenderer) merged.webglRenderer = cfg.webglRenderer;
    }

    // ---- Consistency sanitizer: profile.os ALWAYS wins over stale UA/GPU ----
    try {
      var osRaw = String(merged.os || "").toLowerCase();
      var ua = String(merged.userAgent || "");
      var plat = String(merged.platform || "").toLowerCase();
      // Prefer explicit os field from native config (most reliable)
      var isIOS = osRaw.indexOf("ios") >= 0 || osRaw.indexOf("iphone") >= 0 || osRaw.indexOf("ipad") >= 0 ||
                  /iPhone|iPad|iPod/i.test(ua);
      var isAndroid = !isIOS && (osRaw.indexOf("android") >= 0 || /Android/i.test(ua));
      var isMac = !isIOS && !isAndroid && (
                  osRaw.indexOf("mac") >= 0 ||
                  (/Macintosh|MacIntel/i.test(ua) && !/Android/i.test(ua)));
      var isWin = !isIOS && !isAndroid && !isMac && (
                  osRaw.indexOf("win") >= 0 || /Windows NT|Win32/i.test(ua));
      var isLinux = !isIOS && !isAndroid && !isMac && !isWin && (
                  osRaw.indexOf("linux") >= 0 || (/Linux/i.test(ua) && !/Android/i.test(ua)));
      var renderer = String(merged.webglRenderer || "");

      if (isAndroid) {
        merged.os = "Android";
        // Keep Mali / Adreno from template; only replace desktop/Apple leftovers
        if (!renderer || /Apple|Metal|M3|M2|M1|GeForce|RTX|GTX|Radeon|Direct3D|UHD Graphics|Intel/i.test(renderer)) {
          // Prefer Mali if template name hints Samsung A-series, else Adreno
          merged.webglVendor = /Mali/i.test(renderer) ? "ARM" : "Qualcomm";
          merged.webglRenderer = /Mali/i.test(renderer) ? renderer : "Adreno (TM) 750";
        }
        merged.gpuVendor = merged.webglVendor;
        merged.gpuRenderer = merged.webglRenderer;
        if (!merged.platform || /MacIntel|Win32|iPhone/i.test(String(merged.platform))) {
          merged.platform = "Linux armv8l";
        }
        if (!ua || /Macintosh|Windows NT|iPhone/i.test(ua)) {
          merged.userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
        }
        var swa = merged.screenWidth | 0, sha = merged.screenHeight | 0;
        if (swa >= 2000 || sha >= 2000 || swa < 320 || sha < 320) {
          merged.screenWidth = 1080;
          merged.screenHeight = 2400;
        }
        if (!(merged.devicePixelRatio >= 2)) merged.devicePixelRatio = 2.625;
        if (!(merged.hardwareConcurrency >= 4) || merged.hardwareConcurrency > 16) merged.hardwareConcurrency = 8;
        if (!(merged.deviceMemory >= 4) || merged.deviceMemory > 24) merged.deviceMemory = 8;
      } else if (isIOS) {
        merged.os = "iOS";
        merged.webglVendor = "Apple Inc.";
        merged.webglRenderer = "Apple GPU";
        merged.gpuVendor = "Apple Inc.";
        merged.gpuRenderer = "Apple GPU";
        merged.platform = "iPhone";
        if (!ua || !/iPhone/i.test(ua) || /Chrome\/\d/i.test(ua) && !/CriOS/i.test(ua)) {
          merged.userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
        }
        var swi = merged.screenWidth | 0;
        if (swi >= 1000 || swi < 320) {
          merged.screenWidth = 393;
          merged.screenHeight = 852;
        }
        if (!(merged.devicePixelRatio >= 2)) merged.devicePixelRatio = 3.0;
        if (!(merged.hardwareConcurrency >= 4) || merged.hardwareConcurrency > 8) merged.hardwareConcurrency = 6;
        if (!(merged.deviceMemory >= 4) || merged.deviceMemory > 16) merged.deviceMemory = 6;
      } else if (isMac) {
        merged.os = "macOS";
        var tplHint = String(merged.fingerprintTemplate || merged.name || "");
        // Keep explicit profile GPU if it already looks like a Mac GPU
        if (renderer && /Iris|Apple M[1-4]|Metal Renderer/i.test(renderer) && !/Adreno|Mali|GeForce|RTX|GTX/i.test(renderer)) {
          // keep as-is
        } else if (!renderer || /NVIDIA|GeForce|GTX|RTX|Radeon|Direct3D|D3D11|Adreno|Mali|UHD Graphics/i.test(renderer)) {
          if (/Iris/i.test(tplHint) || /Iris/i.test(renderer)) {
            merged.webglVendor = "Google Inc. (Apple)";
            merged.webglRenderer = "ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)";
          } else if (/M1|M2|M3|M4/i.test(tplHint)) {
            merged.webglVendor = "Google Inc. (Apple)";
            merged.webglRenderer = "ANGLE (Apple, ANGLE Metal Renderer: Apple M3 Max, Unspecified Version)";
          } else {
            merged.webglVendor = "Google Inc. (Apple)";
            merged.webglRenderer = "ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)";
          }
        }
        merged.gpuVendor = merged.webglVendor;
        merged.gpuRenderer = merged.webglRenderer;
        merged.platform = "MacIntel";
        if (!ua || /Android|iPhone|Windows NT/i.test(ua)) {
          merged.userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        }
        var sw = merged.screenWidth | 0;
        if (!(sw >= 1280)) {
          merged.screenWidth = 3024;
          merged.screenHeight = 1964;
        }
        if (!(merged.devicePixelRatio > 1)) merged.devicePixelRatio = 2.0;
        if (!(merged.hardwareConcurrency > 4)) merged.hardwareConcurrency = 12;
        if (!(merged.deviceMemory > 8)) merged.deviceMemory = 16;
      } else if (isWin) {
        merged.os = "Windows";
        // Only fill GPU if missing or clearly wrong OS family – keep RTX 3080 / RX 6800 from template
        if (!renderer || /Apple|Metal|Adreno|Mali|iPhone/i.test(renderer)) {
          merged.webglVendor = "Google Inc. (NVIDIA)";
          merged.webglRenderer = "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)";
        }
        merged.gpuVendor = merged.webglVendor;
        merged.gpuRenderer = merged.webglRenderer;
        merged.platform = "Win32";
        if (!ua || /Android|iPhone|Macintosh/i.test(ua)) {
          merged.userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        }
        if (!(merged.screenWidth > 0)) { merged.screenWidth = 1920; merged.screenHeight = 1080; }
        if (!(merged.devicePixelRatio > 0)) merged.devicePixelRatio = 1.0;
        if (!(merged.hardwareConcurrency > 0)) merged.hardwareConcurrency = 8;
        if (!(merged.deviceMemory > 0)) merged.deviceMemory = 8;
      } else if (isLinux) {
        merged.os = "Linux";
        if (!renderer || /Apple|Metal|Adreno|iPhone|Macintosh/i.test(renderer)) {
          merged.webglVendor = "Intel";
          merged.webglRenderer = "Mesa Intel(R) UHD Graphics 620 (KBL GT2)";
        }
        merged.gpuVendor = merged.webglVendor;
        merged.gpuRenderer = merged.webglRenderer;
        merged.platform = "Linux x86_64";
        if (!ua || /Android|iPhone|Macintosh|Windows NT/i.test(ua)) {
          merged.userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        }
        if (!(merged.screenWidth > 0)) { merged.screenWidth = 1920; merged.screenHeight = 1080; }
        if (!(merged.hardwareConcurrency > 0)) merged.hardwareConcurrency = 8;
        if (!(merged.deviceMemory > 0)) merged.deviceMemory = 8;
      }

      if (!merged.timezone || merged.timezone === "America/New_York" || merged.timezone === "UTC") {
        merged.timezone = "America/Mexico_City";
      }
    } catch (e) {}

    // Allow re-injection when a real profile config arrives
    try {
      window.__antidetectInjected = false;
      window.__antiDetectPageApplied = false;
      window.__antiDetectSig = null;
    } catch (e) {}
    injectPageWorld(merged);
    // CRITICAL: persist to background so other origins (creepjs) get the same config
    try {
      if (typeof browser !== "undefined" && browser.runtime && isUsefulConfig(merged)) {
        browser.runtime.sendMessage({ type: "setConfig", config: merged }).catch(function () {});
      }
    } catch (e) {}
  }

  // Allow native side (ProgressDelegate) to push config at any time
  try {
    window.__gestorApplyConfig = function (cfg) {
      if (cfg && typeof cfg === "object") go(cfg);
    };
  } catch (e) {}

  function isUsefulConfig(cfg) {
    if (!cfg || typeof cfg !== "object") return false;
    // Must have a real GPU string – empty config.json must NOT win
    var r = cfg.webglRenderer || cfg.gpuRenderer || "";
    return String(r).length > 4;
  }

  function loadConfigAndStart() {
    // Keep listening for live updates (new page / profile change)
    try {
      if (typeof browser !== "undefined" && browser.runtime && browser.runtime.onMessage) {
        browser.runtime.onMessage.addListener(function (msg) {
          if (msg && msg.type === "configUpdate" && isUsefulConfig(msg.config)) {
            try { sessionStorage.setItem("__adcfg", JSON.stringify(msg.config)); } catch (e) {}
            go(msg.config);
          }
        });
      }
    } catch (e) {}

    // 0) URL hash #__ad= (compact config from native urlWithConfig)
    try {
      var h = String(location.hash || "");
      var idx = h.indexOf("__ad=");
      if (idx >= 0) {
        var b64 = h.substring(idx + 5).split("&")[0].split("#")[0];
        b64 = b64.replace(/-/g, "+").replace(/_/g, "/");
        while (b64.length % 4) b64 += "=";
        var raw = atob(b64);
        var hc = JSON.parse(raw);
        if (isUsefulConfig(hc)) {
          try { sessionStorage.setItem("__adcfg", raw); } catch (e) {}
          try { history.replaceState(null, "", location.pathname + location.search); } catch (e) {}
          go(hc);
          return;
        }
      }
    } catch (e) {}

    // 0) Loopback ConfigServer (Android app) – always has current profile GPU/screen
    try {
      var xhr = new XMLHttpRequest();
      xhr.open("GET", "http://127.0.0.1:17351/cfg", false); // sync at document_start
      xhr.timeout = 800;
      try { xhr.send(null); } catch (eSend) {}
      if (xhr.status === 200 && xhr.responseText) {
        try {
          var lc = JSON.parse(xhr.responseText);
          if (isUsefulConfig(lc)) {
            try { sessionStorage.setItem("__adcfg", JSON.stringify(lc)); } catch (e) {}
            go(lc);
            return;
          }
        } catch (eParse) {}
      }
    } catch (eXhr) {}
    // async retry in case server was not ready
    try {
      fetch("http://127.0.0.1:17351/cfg", { cache: "no-store", mode: "cors" })
        .then(function (r) { return r.json(); })
        .then(function (lc) {
          if (isUsefulConfig(lc)) {
            try { sessionStorage.setItem("__adcfg", JSON.stringify(lc)); } catch (e) {}
            go(lc);
          }
        })
        .catch(function () {});
    } catch (eFetch) {}

    // 0a) Embedded config from per-profile XPI – fastest, no messaging
    try {
      if (typeof __EMBEDDED_CONFIG !== "undefined" && isUsefulConfig(__EMBEDDED_CONFIG)) {
        go(__EMBEDDED_CONFIG);
        return;
      }
    } catch (e) {}

    // 0) Native-injected config
    try {
      if (typeof window.__GESTOR_CFG === "object" && isUsefulConfig(window.__GESTOR_CFG)) {
        go(window.__GESTOR_CFG);
        return;
      }
    } catch (e) {}

    // 1) URL hash
    try {
      var hash = "";
      try { hash = String(location.hash || ""); } catch (e) {}
      var key = "__adcfg=";
      var idx = hash.indexOf(key);
      if (idx >= 0) {
        var b64 = hash.substring(idx + key.length);
        var amp = b64.indexOf("&");
        if (amp >= 0) b64 = b64.substring(0, amp);
        b64 = b64.replace(/-/g, "+").replace(/_/g, "/");
        while (b64.length % 4) b64 += "=";
        var jsonStr = decodeURIComponent(escape(atob(b64)));
        var cfg = JSON.parse(jsonStr);
        try { sessionStorage.setItem("__adcfg", jsonStr); } catch (e2) {}
        // Keep hash until spoof applied – other scripts may also read it
        if (isUsefulConfig(cfg)) {
          go(cfg);
          // strip hash after successful apply (optional, non-blocking)
          try { setTimeout(function(){ try { history.replaceState(null, "", location.pathname + location.search); } catch(e){} }, 2000); } catch(e){}
          return;
        }
      }
    } catch (e) {}

    // 2) sessionStorage (same origin only)
    try {
      var stored = sessionStorage.getItem("__adcfg");
      if (stored) {
        var sc = JSON.parse(stored);
        if (isUsefulConfig(sc)) {
          go(sc);
          return;
        }
      }
    } catch (e) {}

    // 3) Extension storage – survives origin changes (duckduckgo vs creepjs)
    try {
      if (typeof browser !== "undefined" && browser.storage && browser.storage.local) {
        browser.storage.local.get("gestorCfg").then(function (data) {
          if (data && isUsefulConfig(data.gestorCfg)) {
            go(data.gestorCfg);
          } else {
            requestFromBackground();
          }
        }).catch(function () { requestFromBackground(); });
        return;
      }
    } catch (e) {}

    requestFromBackground();
  }

  function requestFromBackground() {
    try {
      if (typeof browser !== "undefined" && browser.runtime) {
        browser.runtime.sendMessage({ type: "getConfig" }).then(function (resp) {
          var c = resp && (resp.config || resp);
          if (typeof c === "string") {
            try { c = JSON.parse(c); } catch (e) { c = null; }
          }
          if (isUsefulConfig(c)) go(c);
          else tryEmbeddedOrStatic();
        }).catch(function () { tryEmbeddedOrStatic(); });
        return;
      }
    } catch (e) {}
    tryEmbeddedOrStatic();
  }

  function tryEmbeddedOrStatic() {
    try {
      if (typeof __EMBEDDED_CONFIG !== "undefined" && isUsefulConfig(__EMBEDDED_CONFIG)) {
        go(__EMBEDDED_CONFIG);
        return;
      }
    } catch (e) {}
    // Static config.json is often empty – only use if it actually has a GPU string
    try {
      if (typeof browser !== "undefined" && browser.runtime && browser.runtime.getURL) {
        fetch(browser.runtime.getURL("config.json"))
          .then(function (r) { return r.json(); })
          .then(function (json) {
            if (isUsefulConfig(json)) go(json);
            // else: do nothing – wait for native __gestorApplyConfig
          })
          .catch(function () {});
        return;
      }
    } catch (e) {}
  }


  // Aggressive cross-origin recovery: poll background for up to 8s
  // (navigating google → creepjs loses hash + sessionStorage)
  (function aggressiveConfigPoll() {
    var n = 0;
    var iv = setInterval(function () {
      n++;
      try {
        if (typeof browser !== "undefined" && browser.runtime) {
          browser.runtime.sendMessage({ type: "getConfig" }).then(function (resp) {
            var c = resp && (resp.config || resp);
            if (typeof c === "string") { try { c = JSON.parse(c); } catch (e) { c = null; } }
            if (isUsefulConfig(c)) {
              try { sessionStorage.setItem("__adcfg", JSON.stringify(c)); } catch (e) {}
              go(c);
              clearInterval(iv);
            }
          }).catch(function () {});
        }
      } catch (e) {}
      if (n > 40) clearInterval(iv);
    }, 200);
  })();

  loadConfigAndStart();
})();
