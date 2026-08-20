/**
 * EARLY SPOOF – first content script at document_start.
 * Config: #__ad= hash | sessionStorage | embedded | ConfigServer | Iris FALLBACK
 */
(function () {
  "use strict";
  if (window.__earlySpoofRan) return;
  window.__earlySpoofRan = true;

  var FALLBACK = {
    webglVendor: "Google Inc. (Apple)",
    webglRenderer: "ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)",
    gpuVendor: "Google Inc. (Apple)",
    gpuRenderer: "ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)",
    screenWidth: 2560,
    screenHeight: 1600,
    hardwareConcurrency: 12,
    deviceMemory: 16,
    devicePixelRatio: 2,
    platform: "MacIntel",
    os: "macOS",
    language: "es-MX",
    languages: ["es-MX", "es", "en-US"],
    timezone: "America/Mexico_City",
    userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
  };

  function useful(c) {
    return c && String(c.webglRenderer || c.gpuRenderer || "").length > 4;
  }

  function readCfg() {
    try {
      var h = String(location.hash || "");
      var idx = h.indexOf("__ad=");
      if (idx >= 0) {
        var b64 = h.substring(idx + 5).split("&")[0].split("#")[0];
        b64 = b64.replace(/-/g, "+").replace(/_/g, "/");
        while (b64.length % 4) b64 += "=";
        var raw = atob(b64);
        var c = JSON.parse(raw);
        if (useful(c)) {
          try { sessionStorage.setItem("__adcfg", raw); } catch (e) {}
          try { history.replaceState(null, "", location.pathname + location.search); } catch (e) {}
          return c;
        }
      }
    } catch (e) {}
    try {
      var s = sessionStorage.getItem("__adcfg");
      if (s) {
        var c2 = JSON.parse(s);
        if (useful(c2)) return c2;
      }
    } catch (e) {}
    try {
      if (typeof __EMBEDDED_CONFIG !== "undefined" && useful(__EMBEDDED_CONFIG)) return __EMBEDDED_CONFIG;
    } catch (e) {}
    try {
      var xhr = new XMLHttpRequest();
      xhr.open("GET", "http://127.0.0.1:17351/cfg", false);
      xhr.timeout = 400;
      xhr.send(null);
      if (xhr.status === 200 && xhr.responseText) {
        var j = JSON.parse(xhr.responseText);
        if (useful(j)) {
          try { sessionStorage.setItem("__adcfg", xhr.responseText); } catch (e) {}
          return j;
        }
      }
    } catch (e) {}
    return null;
  }

  function buildPayload(cfg) {
    var json = JSON.stringify(cfg);
    return "(function(){try{var c=" + json + ";window.__GESTOR_CFG=c;window.__cfg=c;" +
      "try{sessionStorage.setItem('__adcfg',JSON.stringify(c));}catch(e){}" +
      "var D=Object.defineProperty;function g(o,p,v){try{D(o,p,{get:function(){return v},set:function(){},configurable:true,enumerable:true})}catch(e){}}" +
      "var V=c.webglVendor||c.gpuVendor||'Google Inc. (Apple)';" +
      "var R=c.webglRenderer||c.gpuRenderer||'ANGLE (Apple, ANGLE Metal Renderer: Intel(R) Iris(TM) Plus Graphics, Unspecified Version)';" +
      "var sw=(c.screenWidth|0)||2560,sh=(c.screenHeight|0)||1600;" +
      "var cores=(c.hardwareConcurrency|0)||12,mem=(c.deviceMemory|0)||16;" +
      "var ua=c.userAgent||'',plat=c.platform||'MacIntel';" +
      "var osL=String(c.os||'').toLowerCase();var touch=/android|ios|iphone|ipad/.test(osL)?5:0;" +
      "if(!touch&&(sw<1280||sh<720)){sw=2560;sh=1600;}" +
      "try{var NP=Navigator.prototype;" +
      "if(ua.length>20){g(NP,'userAgent',ua);g(NP,'appVersion',ua.replace(/^Mozilla\\//,''));}" +
      "g(NP,'platform',plat);g(NP,'hardwareConcurrency',cores);g(NP,'deviceMemory',mem);" +
      "g(NP,'maxTouchPoints',touch);g(NP,'vendor','Google Inc.');g(NP,'webdriver',false);" +
      "g(NP,'language',c.language||'es-MX');" +
      "if(Array.isArray(c.languages))g(NP,'languages',Object.freeze(c.languages.slice()));}catch(e){}" +
      "try{var SP=Screen.prototype;g(SP,'width',sw);g(SP,'height',sh);g(SP,'availWidth',sw);" +
      "g(SP,'availHeight',Math.max(sh-40,1));g(SP,'colorDepth',24);g(SP,'pixelDepth',24);" +
      "g(window.screen,'width',sw);g(window.screen,'height',sh);g(window.screen,'availWidth',sw);" +
      "g(window.screen,'availHeight',Math.max(sh-40,1));" +
      "g(window,'devicePixelRatio',(c.devicePixelRatio>0?c.devicePixelRatio:(touch?3:2)));" +
      "if(!touch){g(window,'innerWidth',Math.min(sw,1440));g(window,'innerHeight',Math.min(sh,900));" +
      "g(window,'outerWidth',Math.min(sw,1440));g(window,'outerHeight',Math.min(sh,940));}}catch(e){}" +
      "try{function wgp(orig){if(!orig||orig.__es)return orig;var f=function(p){p=p|0;" +
      "if(p===37445||p===0x9245||p===7936||p===0x1F00)return V;" +
      "if(p===37446||p===0x9246||p===7937||p===0x1F01)return R;" +
      "try{return orig.call(this,p)}catch(e){return null}};try{f.__es=1}catch(e){}return f;}" +
      "function patchCtx(ctx){if(!ctx||ctx.__esGpu)return ctx;try{ctx.getParameter=wgp(ctx.getParameter.bind(ctx));ctx.__esGpu=1}catch(e){}return ctx;}" +
      "if(window.WebGLRenderingContext&&WebGLRenderingContext.prototype){" +
      "WebGLRenderingContext.prototype.getParameter=wgp(WebGLRenderingContext.prototype.getParameter);" +
      "try{var ge=WebGLRenderingContext.prototype.getExtension;WebGLRenderingContext.prototype.getExtension=function(n){" +
      "var e=ge.call(this,n);if(e&&/debug_renderer_info/i.test(String(n||''))){try{e.UNMASKED_VENDOR_WEBGL=37445;e.UNMASKED_RENDERER_WEBGL=37446}catch(x){}}return e}}catch(e){}}" +
      "if(window.WebGL2RenderingContext&&WebGL2RenderingContext.prototype){" +
      "WebGL2RenderingContext.prototype.getParameter=wgp(WebGL2RenderingContext.prototype.getParameter);}" +
      "function wgc(proto){if(!proto||!proto.getContext||proto.getContext.__es)return;var og=proto.getContext;" +
      "proto.getContext=function(t,a){var cx=og.call(this,t,a);try{if(cx&&t&&/webgl/i.test(String(t)))patchCtx(cx)}catch(e){}return cx};proto.getContext.__es=1;}" +
      "wgc(HTMLCanvasElement.prototype);if(typeof OffscreenCanvas!=='undefined')wgc(OffscreenCanvas.prototype);" +
      "}catch(e){}}catch(e){}})();";
  }

  function inject(code) {
    var root = document.documentElement || document.head;
    if (!root) return false;
    try {
      if (typeof browser !== "undefined" && browser.runtime && browser.runtime.getURL) {
        var s0 = document.createElement("script");
        s0.src = browser.runtime.getURL("page-world.js");
        s0.async = false;
        root.insertBefore(s0, root.firstChild);
      }
    } catch (e) {}
    try {
      var s = document.createElement("script");
      s.textContent = code;
      root.insertBefore(s, root.firstChild);
      try { s.remove(); } catch (e) {}
      return true;
    } catch (e) {}
    return false;
  }

  function xrayPatch(cfg) {
    try {
      if (typeof exportFunction !== "function" || !window.wrappedJSObject) return false;
      var pageWin = window.wrappedJSObject;
      var V = cfg.webglVendor || cfg.gpuVendor || FALLBACK.webglVendor;
      var R = cfg.webglRenderer || cfg.gpuRenderer || FALLBACK.webglRenderer;
      var sw = (cfg.screenWidth | 0) || 2560;
      var sh = (cfg.screenHeight | 0) || 1600;
      function def(obj, prop, val) {
        try {
          Object.defineProperty(obj, prop, {
            get: exportFunction(function () { return val; }, pageWin),
            set: exportFunction(function () {}, pageWin),
            configurable: true, enumerable: true
          });
        } catch (e) {}
      }
      try {
        def(pageWin.Screen.prototype, "width", sw);
        def(pageWin.Screen.prototype, "height", sh);
        def(pageWin.Screen.prototype, "availWidth", sw);
        def(pageWin.Screen.prototype, "availHeight", Math.max(sh - 40, 1));
        if (pageWin.screen) {
          def(pageWin.screen, "width", sw);
          def(pageWin.screen, "height", sh);
        }
      } catch (e) {}
      try {
        if (cfg.userAgent) def(pageWin.Navigator.prototype, "userAgent", cfg.userAgent);
        if (cfg.platform) def(pageWin.Navigator.prototype, "platform", cfg.platform);
        def(pageWin.Navigator.prototype, "hardwareConcurrency", (cfg.hardwareConcurrency | 0) || 12);
        def(pageWin.Navigator.prototype, "deviceMemory", (cfg.deviceMemory | 0) || 16);
        def(pageWin.Navigator.prototype, "maxTouchPoints", 0);
        def(pageWin.Navigator.prototype, "webdriver", false);
      } catch (e) {}
      try {
        function wrapGP(orig) {
          return exportFunction(function (pname) {
            var p = pname | 0;
            if (p === 37445 || p === 0x9245 || p === 7936) return V;
            if (p === 37446 || p === 0x9246 || p === 7937) return R;
            return orig.call(this, pname);
          }, pageWin);
        }
        if (pageWin.WebGLRenderingContext)
          pageWin.WebGLRenderingContext.prototype.getParameter = wrapGP(pageWin.WebGLRenderingContext.prototype.getParameter);
        if (pageWin.WebGL2RenderingContext)
          pageWin.WebGL2RenderingContext.prototype.getParameter = wrapGP(pageWin.WebGL2RenderingContext.prototype.getParameter);
      } catch (e) {}
      return true;
    } catch (e) { return false; }
  }

  function apply(cfg) {
    if (!useful(cfg)) cfg = FALLBACK;
    try { sessionStorage.setItem("__adcfg", JSON.stringify(cfg)); } catch (e) {}
    try { window.__GESTOR_CFG = cfg; } catch (e) {}
    inject(buildPayload(cfg));
    xrayPatch(cfg);
  }

  apply(readCfg() || FALLBACK);

  var n = 0;
  var iv = setInterval(function () {
    n++;
    var c = readCfg();
    if (c) apply(c);
    if (n >= 20) clearInterval(iv);
  }, 50);
})();
