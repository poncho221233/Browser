# GestorTDD (Android)

Navegador anti-detect modular para Android basado en **Mozilla GeckoView**.

Cada perfil mantiene un contexto aislado (cookies, storage, cache, fingerprint) y puede emular un dispositivo / SO distinto.

## Características
- 50 fingerprints (Windows, macOS, Linux, Android, iOS/Safari)
- Spoof de GPU, pantalla, cores, timezone, locale, Workers
- Storage aislado por perfil (`contextId`)
- WebRTC **soft**: API viva (reCAPTCHA ok) + sin candidatos host/LAN
- DNS remoto vía SOCKS5
- Canvas / Audio noise determinista por `noiseSeed`
- Spoof page-world mínimo (menos señales detectables)

## Build local
```bash
./gradlew assembleDebug
```

## GitHub Actions
Push a `main`/`master` o dispara `workflow_dispatch`.  
Artefactos: APK debug (y release firmado con debug key para pruebas).

## Notas anti-captcha / fingerprint
1. Elige un template de la lista y **no mezcles** GPU/pantalla de otro OS.
2. Perfiles desktop (Windows/macOS/Linux) reportan screen ≥ 1280×720.
3. WebRTC no se mata: se filtran IPs locales (nativo + JS).
4. Cookies de terceros habilitadas por defecto (Google login).
5. `autoCleanOnExit = false` para calentar el perfil.
6. Cuando uses proxy: SOCKS5 + DNS remoto recomendado.

## Changelog (2026-08-19)
- Fingerprint: se materializa GPU/screen/cores/mem del template al lanzar
- WebRTC soft (prefs nativas + filtro host/LAN, sin throw)
- page-world.js reescrito: menos flags globales, WebGL más robusto
- inject.js: deja de matar RTCPeerConnection
- Mejor aplicación de prefs nativas por reflexión

