# VR Tükör – telefon képernyője a Meta Questen

A telefon képernyőjét wifin átküldi a Meta Questre, ahol egy lebegő 2D panelen jelenik meg.
A panelre a kontrollerrel vagy kézzel rámutatva a telefon úgy kezelhető, mintha a kezedben lenne.

## Felépítés

```
VR/
├── common/   – közös hálózati protokoll (üzenetformátum, portok)
├── phone/    – Telefon app: képernyőrögzítés, H.264 kódolás, TCP szerver, érintés-lejátszás
└── quest/    – Quest app: 2D panel, H.264 dekódolás, érintés továbbítása
```

**Hogyan működik**

1. A telefonon a *VR Tükör – Telefon* app a MediaProjection API-val rögzíti a képernyőt,
   a hardveres H.264 kódolóval tömöríti (max. 1280 px hosszabb oldal, 8 Mbit/s, 60 fps),
   és TCP-n (7788-as port) elküldi a Questnek.
2. A Questen a *VR Tükör* app hardveresen dekódolja a képet közvetlenül a panel felületére.
3. A panelen történt érintéseket a Quest arányos koordinátaként (0..1) visszaküldi.
   A telefonon egy Kisegítő lehetőségek szolgáltatás játssza le őket valódi gesztusként.
4. A Quest UDP broadcasttal (7789-es port) automatikusan megtalálja a telefont.

## Mi kell hozzá

- **Android Studio** (Koala 2024.1 vagy újabb) a gépen. Ez hozza a Java és Android SDK-t is.
- **Telefon:** Android 8.0 (API 26) vagy újabb.
- **Meta Quest** (2 / 3 / 3S / Pro) fejlesztői móddal.
- Mindkét eszköz **ugyanazon a wifi hálózaton**, lehetőleg 5 GHz-en.

## Fordítás

1. Nyisd meg Android Studióban a `VR` mappát (*File → Open*).
2. Első megnyitáskor a Studio letölti a Gradle-t és az SDK-t (compileSdk 34). Ha felajánlja az SDK
   hiányzó részeinek telepítését, fogadd el.
3. Két futtatási konfiguráció lesz: `phone` és `quest`.

**Parancssorból** (Android Studio nélkül is megy, csak JDK 17 és az Android SDK kell):

```bash
set JAVA_HOME=%LOCALAPPDATA%\Programs\jdk-17
gradlew.bat assembleDebug
```

Az APK-k ide kerülnek: `phone/build/outputs/apk/debug/phone-debug.apk` és
`quest/build/outputs/apk/debug/quest-debug.apk`. A `local.properties` fájlban az `sdk.dir` mutat az SDK-ra
(alapból `%LOCALAPPDATA%\Android\Sdk`).

## Telepítés a telefonra

1. Kapcsold be a telefonon a fejlesztői módot és az USB-hibakeresést.
2. Android Studióban válaszd a `phone` konfigurációt és a telefont, majd *Run* (▶).
   (Az `adb install` telepítés azért is jó, mert így az Android 13+ nem korlátozza a Kisegítő
   lehetőségek szolgáltatás engedélyezését.)
3. A telefonon nyisd meg az appot, nyomd meg az **Érintésvezérlés beállítása…** gombot, és
   engedélyezd a *VR Tükör érintésvezérlés* szolgáltatást (Letöltött appok alatt).
   Ha az Android „Korlátozott beállítás” üzenetet ad: Beállítások → Alkalmazások → VR Tükör → ⋮ →
   *Korlátozott beállítások engedélyezése*, utána próbáld újra.
4. **Megosztás indítása** → engedélyezd a képernyőrögzítést. Az app kiírja a telefon IP-címét.

## Telepítés a Questre

1. A Meta Quest mobilappban: *Eszközök → Headset → Fejlesztői mód* bekapcsolása
   (ehhez fejlesztői fiók kell a developer.oculus.com oldalon, ingyenes).
2. Kösd a Questet USB-n a géphez, a headsetben fogadd el az USB-hibakeresést.
3. Android Studióban válaszd a `quest` konfigurációt és a Questet, majd *Run*.
   Vagy kézzel:
   ```bash
   adb install -r quest/build/outputs/apk/debug/quest-debug.apk
   ```
4. A Questen: *Alkalmazások → szűrő: Ismeretlen források → VR Tükör*.

## Használat

1. A telefonon fusson a megosztás.
2. A Questen nyisd meg a VR Tükör appot. Nyomj a **Keresés** gombra, vagy írd be az IP-címet és
   **Csatlakozás**.
3. A panel méretét a sarkánál fogva állíthatod, és bárhova elhelyezheted.
4. Az alsó sávon a **Vissza / Kezdőlap / Appok** gombok a telefon rendszergombjai.

## Hibaelhárítás

| Tünet | Mit nézz meg |
|---|---|
| A Keresés nem talál semmit | Fut a megosztás a telefonon? Ugyanaz a wifi? Egyes routerek tiltják a broadcastot (AP isolation) – ilyenkor írd be kézzel az IP-t. |
| Csatlakozva, de nincs kép | Tűzfal/hálózati szűrés a 7788-as porton; próbáld újraindítani a megosztást. |
| A kép megy, de az érintés nem | A telefonon nincs engedélyezve az érintésvezérlés (az app főképernyője mutatja). |
| Fekete a kép egy appban | Az app védett (FLAG_SECURE vagy DRM), pl. Netflix, banki app. Ezt az Android tiltja, nem kerülhető meg. |
| Szaggat | Kapcsolj 5 GHz-es wifire; a `ScreenEncoder.BITRATE` és `MAX_LONG_EDGE` értéke csökkenthető. |

## Hang

- A telefonon lejátszott hang (média, játék) átmegy a Questre, kb. 100–200 ms késéssel.
- A telefonos app „Telefon némítása” pipája csatlakozáskor nullára veszi a médiahangerőt, bontáskor visszaállítja.
- **Hívások hangja nem megy át** (telefonhívás, Messenger, WhatsApp): az Android a hívás típusú hangot
  kizárja a rögzítésből, és a Quest mikrofonja sem adható át a telefon appjainak. Hívásokhoz a Quest
  böngészőjében a messenger.com / web.whatsapp.com használható, a headset mikrofonjával és hangszórójával.

## Görgetés

- Húzás a panelen (ravasz + mozgatás) és a hüvelykujj-kar is görget. A kar görgetését a telefon
  folyamatos húzásként játssza le, a lépésköz a `TouchInjectorService.handleScroll` függvényben állítható.

## Ismert korlátok

- Egy ujjas érintés, húzás, görgetés működik. Kétujjas csippentés nincs.
- Védett tartalom (DRM, FLAG_SECURE) feketén jelenik meg.
- Kézzel lezárt telefonnál a rendszer leállítja a rögzítést; feloldás után az értesítésre koppintva
  indul újra. Csatlakozott Quest mellett a telefon magától nem zárolódik, csak elsötétül.
- A panel álló tájolású; ha a telefon fekvőbe fordul, a kép a panelen belül fekvőben, kisebben látszik.
- Immerzív VR-appok futása alatt a panel nem látható, csak a Home környezetben és passthrough alatt.

## Továbbfejlesztési ötletek

- Kétujjas gesztusok (második mutató a protokollban + második stroke).
- Unity/OpenXR alapú kliens, hogy VR-appok közben is látszódjon.
