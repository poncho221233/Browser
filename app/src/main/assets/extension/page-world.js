/**
 * PAGE WORLD spoof – loaded via extension URL (CSP-safe).
 * Minimal surface: no obvious global flags, soft WebRTC (filter host only),
 * early WebGL/screen/navigator patches.
 */
(function () {
  "use strict";
  if (window.__pwApplied) return;
  window.__pwApplied = 1;

  function readCfg() {
    try {
      var raw = sessionStorage.getItem("__adcfg");
      if (raw) {
        var c = JSON.parse(raw);
        if (c && (c.webglRenderer || c.userAgent)) return c;
      }
    } catch (e) {}
    try {
      if (window.__cfg && (window.__cfg.webglRenderer || window.__cfg.userAgent)) return window.__cfg;
    } catch (e) {}
    try {
      var h = location.hash || "";
      var i = h.indexOf("__adcfg=");
      if (i >= 0) {
        var b64 = h.substring(i + 8).split("&")[0].split("#")[0];
        var json = atob(b64.replace(/-/g, "+").replace(/_/g, "/"));
        return JSON.parse(json);
      }
    } catch (e) {}
    return null;
  }

  function tzOffsetMinutes(tz) {
    try {
      var parts = new Intl.DateTimeFormat("en-US", {
        timeZone: tz || "UTC",
        timeZoneName: "longOffset"
      }).formatToParts(new Date());
      var off = "";
      for (var i = 0; i < parts.length; i++) {
        if (parts[i].type === "timeZoneName") off = parts[i].value;
      }
      var m = /GMT([+-])(\d{1,2})(?::?(\d{2}))?/.exec(off);
      if (m) {
        var sign = m[1] === "-" ? 1 : -1;
        var hh = parseInt(m[2], 10) || 0;
        var mm = parseInt(m[3] || "0", 10) || 0;
        return sign * (hh * 60 + mm);
      }
    } catch (e) {}
    // Fallback common zones
    var map = {
      "America/Mexico_City": 360,
      "America/New_York": 300,
      "America/Los_Angeles": 480,
      "Europe/Madrid": -60,
      "UTC": 0
    };
    return map[tz] != null ? map[tz] : 360;
  }

  function apply(cfg) {
    if (!cfg) return;
    var hasGpu = !!(cfg.webglRenderer && String(cfg.webglRenderer).length > 4);
    var hasUa = !!(cfg.userAgent && String(cfg.userAgent).length > 20);
    if (!hasGpu && !hasUa) return;

    try { window.__cfg = cfg; } catch (e) {}
    try { sessionStorage.setItem("__adcfg", JSON.stringify(cfg)); } catch (e) {}

    var d = Object.defineProperty;
    function g(o, p, v) {
      try {
        d(o, p, {
          get: function () { return v; },
          set: function () {},
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    var ua = cfg.userAgent || "";
    var plat = cfg.platform || "Win32";
    var lang = cfg.language || "es-MX";
    var langs = Array.isArray(cfg.languages) && cfg.languages.length
      ? cfg.languages
      : [lang, "es", "en-US", "en"];
    var cores = (cfg.hardwareConcurrency | 0) || 8;
    var mem = (cfg.deviceMemory | 0) || 8;
    var sw = (cfg.screenWidth | 0) || 1920;
    var sh = (cfg.screenHeight | 0) || 1080;
    var dpr = (typeof cfg.devicePixelRatio === "number" && cfg.devicePixelRatio > 0)
      ? cfg.devicePixelRatio
      : 1;
    var V = cfg.webglVendor || cfg.gpuVendor || "Google Inc. (NVIDIA)";
    var R = cfg.webglRenderer || cfg.gpuRenderer ||
      "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Direct3D11 vs_5_0 ps_5_0, D3D11)";
    var tz = cfg.timezone || "America/Mexico_City";
    var osL = String(cfg.os || "").toLowerCase();
    var isMobile = osL.indexOf("android") >= 0 || osL.indexOf("ios") >= 0 ||
      osL.indexOf("iphone") >= 0 || osL.indexOf("ipad") >= 0;
    var touch = isMobile ? 5 : 0;

    // Desktop profiles must never report phone-sized screens
    if (!isMobile && (sw < 1280 || sh < 720)) {
      sw = 1920;
      sh = 1080;
    }
    // Mobile profiles: keep reasonable phone bounds if missing
    if (isMobile && (sw < 320 || sh < 480)) {
      sw = 1080;
      sh = 2400;
    }

    // ---- Navigator ----
    try {
      var NP = Navigator.prototype;
      if (ua.length > 20) {
        g(NP, "userAgent", ua);
        g(NP, "appVersion", ua.replace(/^Mozilla\//, ""));
      }
      g(NP, "platform", plat);
      g(NP, "language", lang);
      g(NP, "languages", Object.freeze(langs.slice()));
      g(NP, "hardwareConcurrency", cores);
      g(NP, "deviceMemory", mem);
      g(NP, "maxTouchPoints", touch);
      g(NP, "vendor", "Google Inc.");
      g(NP, "webdriver", false);
      g(NP, "doNotTrack", null);
      try {
        if (navigator) {
          if (ua.length > 20) g(navigator, "userAgent", ua);
          g(navigator, "platform", plat);
          g(navigator, "hardwareConcurrency", cores);
          g(navigator, "deviceMemory", mem);
          g(navigator, "maxTouchPoints", touch);
        }
      } catch (e) {}
    } catch (e) {}

    // ---- Screen (prototype + instance – Gecko often ignores prototype-only) ----
    try {
      var SP = Screen.prototype;
      g(SP, "width", sw);
      g(SP, "height", sh);
      g(SP, "availWidth", sw);
      g(SP, "availHeight", Math.max(sh - 40, 1));
      g(SP, "colorDepth", 24);
      g(SP, "pixelDepth", 24);
      try {
        g(window.screen, "width", sw);
        g(window.screen, "height", sh);
        g(window.screen, "availWidth", sw);
        g(window.screen, "availHeight", Math.max(sh - 40, 1));
        g(window.screen, "colorDepth", 24);
        g(window.screen, "pixelDepth", 24);
      } catch (e) {}
      try {
        g(window, "devicePixelRatio", dpr);
        // For desktop spoof on a phone, report desktop-like viewport sizes
        if (!isMobile) {
          g(window, "innerWidth", Math.min(sw, 1440));
          g(window, "innerHeight", Math.min(sh, 900));
          g(window, "outerWidth", Math.min(sw, 1440));
          g(window, "outerHeight", Math.min(sh, 940));
        }
      } catch (e) {}
    } catch (e) {}

    // ---- WebGL (must catch every new context) ----
    try {
      function wrapGP(orig) {
        if (!orig || orig.__pw) return orig;
        var fn = function (p) {
          p = p | 0;
          // UNMASKED_VENDOR / RENDERER + VENDOR / RENDERER
          if (p === 37445 || p === 0x9245 || p === 7936 || p === 0x1f00) return V;
          if (p === 37446 || p === 0x9246 || p === 7937 || p === 0x1f01) return R;
          try { return orig.call(this, p); } catch (e) { return null; }
        };
        try { fn.__pw = 1; } catch (e) {}
        try {
          fn.toString = function () { return "function getParameter() { [native code] }"; };
        } catch (e) {}
        return fn;
      }
      function patchCtx(ctx) {
        if (!ctx || ctx.__pwGpu) return ctx;
        try {
          ctx.getParameter = wrapGP(ctx.getParameter.bind(ctx));
          ctx.__pwGpu = 1;
        } catch (e) {}
        return ctx;
      }
      function patchProto(Proto) {
        if (!Proto || !Proto.prototype) return;
        try {
          Proto.prototype.getParameter = wrapGP(Proto.prototype.getParameter);
        } catch (e) {}
        try {
          var ogE = Proto.prototype.getExtension;
          if (ogE && !ogE.__pw) {
            Proto.prototype.getExtension = function (n) {
              var e = ogE.call(this, n);
              if (e && /debug_renderer_info/i.test(String(n || ""))) {
                try {
                  e.UNMASKED_VENDOR_WEBGL = 37445;
                  e.UNMASKED_RENDERER_WEBGL = 37446;
                } catch (x) {}
              }
              return e;
            };
            Proto.prototype.getExtension.__pw = 1;
          }
        } catch (e) {}
      }
      if (typeof WebGLRenderingContext !== "undefined") patchProto(WebGLRenderingContext);
      if (typeof WebGL2RenderingContext !== "undefined") patchProto(WebGL2RenderingContext);

      function wrapGC(proto) {
        if (!proto || !proto.getContext || proto.getContext.__pw) return;
        var og = proto.getContext;
        proto.getContext = function (type, attrs) {
          var ctx = og.call(this, type, attrs);
          try {
            if (ctx && type && /webgl/i.test(String(type))) patchCtx(ctx);
          } catch (e) {}
          return ctx;
        };
        proto.getContext.__pw = 1;
        try {
          proto.getContext.toString = function () {
            return "function getContext() { [native code] }";
          };
        } catch (e) {}
      }
      try { wrapGC(HTMLCanvasElement.prototype); } catch (e) {}
      try {
        if (typeof OffscreenCanvas !== "undefined") wrapGC(OffscreenCanvas.prototype);
      } catch (e) {}
    } catch (e) {}

    // ---- Timezone ----
    try {
      var off = tzOffsetMinutes(tz);
      Date.prototype.getTimezoneOffset = function () { return off; };
    } catch (e) {}
    try {
      if (Intl && Intl.DateTimeFormat) {
        var ro = Intl.DateTimeFormat.prototype.resolvedOptions;
        Intl.DateTimeFormat.prototype.resolvedOptions = function () {
          var o = ro.apply(this, arguments);
          try { o.timeZone = tz; } catch (e) {}
          return o;
        };
      }
    } catch (e) {}

    // ---- WebRTC: ALWAYS soft – drop host/LAN candidates only (never throw).
    // Throwing breaks Google / reCAPTCHA and is more detectable than filtering.
    try {
      function softRtc(Orig) {
        if (!Orig || Orig.__pwRtc) return Orig;
        function PC(cfg, opts) {
          var pc = new Orig(cfg, opts);
          try {
            var origAdd = pc.addEventListener.bind(pc);
            pc.addEventListener = function (type, fn, opt) {
              if (type === "icecandidate" && typeof fn === "function") {
                return origAdd(type, function (ev) {
                  if (ev && ev.candidate && ev.candidate.candidate) {
                    var c = ev.candidate.candidate;
                    if (/ typ host /.test(c) ||
                        / typ srflx /.test(c) && /192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\./.test(c) ||
                        /192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\./.test(c)) {
                      return;
                    }
                  }
                  return fn.call(pc, ev);
                }, opt);
              }
              return origAdd(type, fn, opt);
            };
            var _onic = null;
            Object.defineProperty(pc, "onicecandidate", {
              get: function () { return _onic; },
              set: function (fn) {
                _onic = typeof fn === "function"
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
          } catch (e) {}
          return pc;
        }
        PC.prototype = Orig.prototype;
        try { PC.__pwRtc = 1; } catch (e) {}
        try {
          PC.toString = function () { return "function RTCPeerConnection() { [native code] }"; };
        } catch (e) {}
        return PC;
      }
      if (window.RTCPeerConnection) {
        window.RTCPeerConnection = softRtc(window.RTCPeerConnection);
      }
      if (window.webkitRTCPeerConnection) {
        window.webkitRTCPeerConnection = softRtc(window.webkitRTCPeerConnection);
      }
      if (window.mozRTCPeerConnection) {
        window.mozRTCPeerConnection = softRtc(window.mozRTCPeerConnection);
      }
    } catch (e) {}
  }

  var cfg0 = readCfg();
  if (cfg0) apply(cfg0);

  window.addEventListener("message", function (ev) {
    try {
      if (ev && ev.data && (ev.data.__gestorPageCfg || ev.data.__pwCfg)) {
        apply(ev.data.__gestorPageCfg || ev.data.__pwCfg);
      }
    } catch (e) {}
  });

  // Compatible with older inject.js callers
  window.__gestorApplyConfig = function (c) {
    try { sessionStorage.setItem("__adcfg", JSON.stringify(c)); } catch (e) {}
    apply(c);
  };

  var n = 0;
  var iv = setInterval(function () {
    n++;
    var c = readCfg();
    if (c) apply(c);
    if (n > 40) clearInterval(iv);
  }, 50);
})();
