# Kompatibilität

Getestet am 22.10.2026 (Version 0.2) und am 24./25.10.2026 (Version 0.3) auf einem echten dedizierten Server mit:

| Teil | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.164.0+26.3 |
| Fabric Loom | 1.18-SNAPSHOT (Plugin `net.fabricmc.fabric-loom`) |
| Gradle | 9.8.2 (per Wrapper) |
| Java | 25 (Temurin 25.0.5) |
| BlueMap | 5.29 (Fabric), API 2.7.x |

- Andere 26.x-Versionen: alle Versionen in `fabric-mod/gradle.properties` **gemeinsam** ändern (Werte von https://fabricmc.net/develop/), dann in `fabric.mod.json` die `minecraft`-Version anpassen und neu bauen. Zwischen Versionen benennt Mojang manchmal Methoden um. Dann meldet der Compiler genau, wo.
- BlueMap ist optional. Ohne BlueMap funktioniert alles außer der Live-Welt und der Anzeige in BlueMap.
- Browser: getestet mit Chromium (Playwright), auch in Handy-Größe (390 × 844). Die Seite nutzt moderne Funktionen
  (ES-Module, `<dialog>`, `color-mix`), also einen aktuellen Chrome, Edge, Firefox oder Safari.
- Node.js 22 nur für die Web-Unit-Tests, Python 3 und Playwright nur für die Integrationstests – nicht für den Betrieb.
- Nur dedizierter Server (keine Einzelspieler-/LAN-Welt).
