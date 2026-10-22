# Kompatibilität

Getestet am 22.10.2026 auf einem echten dedizierten Server mit:

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
- BlueMap ist optional. Ohne BlueMap funktioniert alles außer den 3D-Tabs und der Anzeige in BlueMap.
- Nur dedizierter Server (keine Einzelspieler-/LAN-Welt).
