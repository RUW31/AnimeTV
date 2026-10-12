# AnimeTV — Android TV (Kotlin + Leanback + Media3)

App para Android TV que usa un WebView oculto para extraer el catálogo, los
episodios y el enlace de video (.m3u8 / .mp4) de un sitio web, y lo reproduce con
ExoPlayer (Media3). Toda la navegación funciona con el D-pad del control remoto.

## Estructura

```
AnimeTV/
├── settings.gradle.kts
├── build.gradle.kts                 ← versiones de AGP y Kotlin
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts             ← dependencias (Leanback, Media3, Glide…)
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/animetv/
        │   ├── SiteConfig.kt          ← URL, selectores y JavaScript inyectado (lo único que ajustas)
        │   ├── ScraperWebView.kt      ← WebView oculto: carga, inyecta JS, captura .m3u8/.mp4
        │   ├── ScraperHostActivity.kt ← base: monta el WebView y el overlay de carga
        │   ├── MainActivity.kt        ← BrowseSupportFragment con filas por sección
        │   ├── EpisodesActivity.kt    ← cuadrícula de episodios
        │   ├── PlayerActivity.kt      ← busca el stream y lo reproduce con ExoPlayer
        │   ├── CardPresenter.kt       ← tarjetas de póster (ImageCardView + Glide)
        │   └── Models.kt
        └── res/  (layouts, strings, colores, tema, banner de TV)
```

## Cómo funciona

1. **Catálogo** – `MainActivity` carga `SiteConfig.BASE_URL` en el WebView, inyecta
   `JS_CATALOG` y agrupa los resultados por el título (`h1/h2/h3`) de la sección
   donde aparece cada tarjeta. Cada grupo es una fila de `BrowseSupportFragment`.
2. **Episodios** – `EpisodesActivity` abre la ficha del anime e inyecta `JS_EPISODES`,
   que toma los enlaces cuya ruta cumple `EPISODE_PATTERN`. Se ordenan por número.
3. **Video** – `PlayerActivity` abre la página del episodio y combina tres técnicas:
   - `shouldInterceptRequest` vigila **todas** las peticiones (también las de iframes
     de otros dominios) y captura cualquier URL `.m3u8` / `.mp4`.
   - `JS_FIND_MEDIA` revisa `<video>`, `<source>` y el HTML (incluye URLs escapadas en JSON).
   - Toques sintéticos en el centro para que el reproductor web pida el stream.
   Si no aparece nada, abre cada iframe/servidor embebido (`JS_EMBEDS`) con el Referer correcto.
4. **Reproducción** – ExoPlayer recibe el Referer, el User-Agent y las cookies del
   WebView; sin eso, muchos CDN responden 403.

> El WebView está **debajo** de la UI (tapado y sin foco). Se hace así en vez de
> `visibility="gone"` porque Chromium pausa o limita las páginas que no considera visibles.

`BrowseFragment` está obsoleto; el proyecto usa su reemplazo `BrowseSupportFragment`.

## Compilar en Android Studio

1. Instala **Android Studio** (versión reciente) con el SDK de **Android 16 (API 36)**.
2. Descomprime el ZIP y abre la carpeta `AnimeTV` con **File › Open**.
3. Espera el **Gradle Sync** (descarga Gradle 8.13 y las dependencias la primera vez).
   - Si Android Studio ofrece actualizar AGP o librerías, puedes aceptar.
   - Si pide JDK, usa el que trae Android Studio (*Settings › Build › Gradle › Gradle JDK › jbr-21*).
4. Crea un emulador de TV: **Device Manager › + › TV › Television (1080p)** con una
   imagen de sistema **Google TV / Android TV API 34+**.
5. Pulsa **Run ▶**. Navega con las flechas del teclado (D-pad), Enter (OK) y Esc (Atrás).

### Instalar en un televisor real

1. En la TV: *Ajustes › Sistema › Acerca de › Compilación* (pulsa 7 veces) para activar
   **Opciones de desarrollador**, y dentro activa **Depuración por red / ADB**.
2. En la PC: `adb connect IP_DE_LA_TV:5555` (acepta el aviso en la TV).
3. Selecciona la TV en Android Studio y pulsa **Run ▶**, o genera el APK con
   **Build › Build App Bundle(s) / APK(s) › Build APK(s)** e instálalo con
   `adb install app/build/outputs/apk/debug/app-debug.apk`.

### Instalar en la TV sin PC (GitHub Actions)

Cada `push` a `main` compila el APK en la nube (`.github/workflows/build-apk.yml`)
y lo publica en *Releases*. El repo debe ser **público** para que la TV pueda
descargarlo sin iniciar sesión.

1. En la TV instala **Downloader** (de AFTVnews) desde la Play Store.
2. **Ajustes › Aplicaciones › Seguridad y restricciones › Fuentes desconocidas** y
   activa **Downloader**.
3. En Downloader escribe:
   `https://github.com/<usuario>/<repo>/releases/latest/download/app-debug.apk`
4. Pulsa **Instalar**. Para actualizar, repite el paso 3: el APK siempre se firma
   con `app/debug.keystore`, así que se instala encima de la versión anterior.

## Ajustar el scraper al sitio (importante)

Los selectores vienen en modo **heurístico** porque cada sitio tiene un HTML distinto.
Si el catálogo o los episodios salen vacíos:

1. Abre el sitio en Chrome de escritorio › **F12** › pestaña *Elements*.
2. Inspecciona una tarjeta de anime y pon su selector en `SiteConfig.CARD_SELECTOR`
   (p. ej. `article.item` o `.anime-card`).
3. Mira cómo son las URL de los episodios y ajusta `EPISODE_PATTERN`
   (p. ej. `-episodio-\d+` o `/ver/`).
4. Prueba cada script pegándolo en la consola de DevTools: debe devolver un array.

### Depurar el WebView oculto

- En builds *debug*, abre `chrome://inspect` en Chrome de la PC con la TV/emulador
  conectado por ADB: verás el WebView oculto, su consola y su pestaña *Network*.
- En **Logcat** filtra por la etiqueta `Scraper`: muestra cuántos animes/episodios
  se extrajeron, cada `.m3u8/.mp4` detectado y las navegaciones bloqueadas.

## Limitaciones

- Si el sitio cambia su HTML, hay que ajustar `SiteConfig.kt`.
- No funciona con streams protegidos con DRM (Widevine) ni con reproductores que
  solo exponen `blob:` sin pedir un `.m3u8` por red.
- Si un servidor devuelve **403**, casi siempre falta Referer/cookie: revisa en
  `chrome://inspect › Network` qué cabeceras envía el navegador y añádelas en
  `PlayerActivity.startPlayback()`.

## Controles en el reproductor

| Tecla            | Acción                                  |
|------------------|-----------------------------------------|
| OK / Centro      | Pausa / reanuda                         |
| ◀ / ▶            | −10 s / +10 s                           |
| ▲ / ▼            | Muestra los controles y navega por ellos |
| Atrás            | Oculta los controles; otra vez, sale    |
| Teclas multimedia| Play/Pausa, adelantar, retroceder       |
