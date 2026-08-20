/**
 * Background – holds fingerprint config across ALL origins.
 * Content scripts on creepjs.com / google.com / any host ask here.
 * Per-profile XPI may prepend: var __EMBEDDED_CONFIG = {...};
 */
var currentConfig = (typeof __EMBEDDED_CONFIG !== "undefined" && __EMBEDDED_CONFIG && __EMBEDDED_CONFIG.webglRenderer)
  ? __EMBEDDED_CONFIG
  : null;
try {
  if (currentConfig && browser.storage && browser.storage.local) {
    browser.storage.local.set({ gestorCfg: currentConfig }).catch(function () {});
  }
} catch (e) {}

function isUseful(cfg) {
  if (!cfg || typeof cfg !== "object") return false;
  var r = cfg.webglRenderer || cfg.gpuRenderer || "";
  return String(r).length > 4;
}

function persistConfig(cfg) {
  if (!isUseful(cfg)) return;
  currentConfig = cfg;
  try {
    if (browser.storage && browser.storage.local) {
      browser.storage.local.set({ gestorCfg: cfg }).catch(function () {});
    }
  } catch (e) {}
  // Push to every open tab so origin switches still get the spoof
  try {
    browser.tabs.query({}).then(function (tabs) {
      for (var i = 0; i < tabs.length; i++) {
        try {
          browser.tabs
            .sendMessage(tabs[i].id, { type: "configUpdate", config: currentConfig })
            .catch(function () {});
        } catch (e) {}
      }
    });
  } catch (e) {}
}

// Restore on startup
try {
  if (browser.storage && browser.storage.local) {
    browser.storage.local.get("gestorCfg").then(function (data) {
      if (data && isUseful(data.gestorCfg)) currentConfig = data.gestorCfg;
    }).catch(function () {});
  }
} catch (e) {}

// Content-script ↔ background
browser.runtime.onMessage.addListener(function (message, sender, sendResponse) {
  try {
    if (!message) {
      sendResponse({ config: currentConfig });
      return true;
    }
    if (message.type === "setConfig" && message.config) {
      var c = message.config;
      if (typeof c === "string") {
        try { c = JSON.parse(c); } catch (e) { c = null; }
      }
      persistConfig(c);
      sendResponse({ ok: true, config: currentConfig });
      return true;
    }
    if (message.type === "getConfig") {
      sendResponse({ config: currentConfig });
      return true;
    }
    // Bare config object from native side
    if (message.webglRenderer || message.gpuRenderer || message.userAgent) {
      persistConfig(message);
      sendResponse({ ok: true });
      return true;
    }
  } catch (e) {}
  sendResponse({ config: currentConfig });
  return true;
});

// Native messaging port (GeckoView MessageDelegate uses app name "browser")
var nativePort = null;
function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("browser");
    nativePort.onMessage.addListener(function (msg) {
      try {
        if (!msg) return;
        if (typeof msg === "string") {
          try { persistConfig(JSON.parse(msg)); } catch (e) {}
          return;
        }
        if (msg.type === "setConfig" && msg.config) {
          var c = msg.config;
          if (typeof c === "string") {
            try { c = JSON.parse(c); } catch (e) { c = null; }
          }
          persistConfig(c);
          return;
        }
        if (msg.config) {
          var c2 = msg.config;
          if (typeof c2 === "string") {
            try { c2 = JSON.parse(c2); } catch (e) { c2 = null; }
          }
          persistConfig(c2);
          return;
        }
        if (msg.webglRenderer || msg.gpuRenderer) persistConfig(msg);
      } catch (e) {}
    });
    nativePort.onDisconnect.addListener(function () {
      nativePort = null;
      setTimeout(connectNative, 1500);
    });
    try { nativePort.postMessage({ type: "getConfig" }); } catch (e) {}
  } catch (e) {
    setTimeout(connectNative, 2500);
  }
}
connectNative();

// Client Hints headers when UA is Chromium-like
function isChromiumUA(ua) {
  if (!ua || typeof ua !== "string") return false;
  if (/Firefox\//i.test(ua) && !/Seamonkey/i.test(ua)) return false;
  return /Chrome\//i.test(ua) || /CriOS\//i.test(ua) || /Edg\//i.test(ua);
}
function extractChromeVersion(ua) {
  var m = ua.match(/(?:Chrome|CriOS|Edg|OPR|Chromium)\/(\d+)/i);
  return m ? m[1] : "126";
}
function buildClientHintHeaders(cfg) {
  var ua = (cfg && cfg.userAgent) || "";
  if (!isChromiumUA(ua)) return null;
  var version = extractChromeVersion(ua);
  var os = String((cfg && cfg.os) || "").toLowerCase();
  var isMobile = /android|iphone|ipad|mobile/i.test(ua) || /android/i.test(os);
  var chPlatform = "Windows";
  if (/mac/i.test(os) || /Macintosh/i.test(ua)) chPlatform = "macOS";
  else if (/android/i.test(os) || /android/i.test(ua)) chPlatform = "Android";
  else if (/linux/i.test(os)) chPlatform = "Linux";
  var ch = (cfg && cfg.clientHints) || {};
  return {
    "sec-ch-ua":
      '"Chromium";v="' + version + '", "Google Chrome";v="' + version + '", "Not-A.Brand";v="99"',
    "sec-ch-ua-mobile": isMobile ? "?1" : "?0",
    "sec-ch-ua-platform": '"' + (ch.platform || chPlatform) + '"'
  };
}

try {
  browser.webRequest.onBeforeSendHeaders.addListener(
    function (details) {
      if (!currentConfig) return {};
      var headers = buildClientHintHeaders(currentConfig);
      if (!headers) return {};
      var requestHeaders = details.requestHeaders || [];
      var keys = Object.keys(headers);
      for (var i = 0; i < keys.length; i++) {
        var name = keys[i];
        var value = headers[name];
        var idx = -1;
        for (var j = 0; j < requestHeaders.length; j++) {
          if (requestHeaders[j].name && requestHeaders[j].name.toLowerCase() === name.toLowerCase()) {
            idx = j;
            break;
          }
        }
        if (idx >= 0) requestHeaders[idx].value = value;
        else requestHeaders.push({ name: name, value: value });
      }
      return { requestHeaders: requestHeaders };
    },
    { urls: ["<all_urls>"] },
    ["blocking", "requestHeaders"]
  );
} catch (e) {}

try {
  browser.webRequest.onAuthRequired.addListener(
    function () {
      if (currentConfig && currentConfig.proxyUsername) {
        return {
          authCredentials: {
            username: currentConfig.proxyUsername,
            password: currentConfig.proxyPassword || ""
          }
        };
      }
    },
    { urls: ["<all_urls>"] },
    ["blocking"]
  );
} catch (e) {}
