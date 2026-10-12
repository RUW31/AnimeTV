# AnimeTV — Android TV (Kotlin + Leanback + Media3)

App para Android TV que muestra el catálogo de un sitio web con una interfaz de TV
(filas, biblioteca, búsqueda, fichas) y abre cada episodio en la propia página del
sitio, con su reproductor, manejable con el D-pad del control remoto.

## Estructura

```
AnimeTV/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── .github/workflows/build-apk.yml   ← compila el APK en GitHub y lo publica en Releases
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/example/animetv/
    │   ├── SiteConfig.kt          ← URL, selectores y JavaScript inyectado (lo único que ajustas)
    │   ├── ScraperWebView.kt      ← WebView oculto que lee catálogo, biblioteca, búsqueda y fichas
    │   ├── ScraperHostActivity.kt ← base: WebView oculto, fondo difuminado y overlay de carga
    │   ├── MainActivity.kt        ← Inicio: Continuar viendo, Favoritos, Explorar, secciones, Géneros
    │   ├── LibraryActivity.kt     ← Biblioteca / géneros en cuadrícula con carga infinita
    │   ├── SearchActivity.kt      ← Búsqueda con teclado o voz
    │   ├── DetailsActivity.kt     ← Ficha: sinopsis, géneros, botones y episodios
    │   ├── WebPlayerActivity.kt   ← Abre el episodio con el reproductor de la página (cursor + modo cine)
    │   ├── CursorView.kt          ← Puntero que se mueve con el D-pad
    │   ├── PlayerActivity.kt      ← Reproductor nativo (ExoPlayer) solo para enlaces directos .m3u8/.mp4
    │   ├── CardPresenter.kt       ← Tarjetas de póster, menú y episodio
    │   ├── Store.kt               ← Favoritos y progreso guardados en la TV
    │   └── Nav.kt / Models.kt / Util.kt
    └── res/  (layouts, strings, colores, tema, íconos, banner de TV)
```

## Cómo funciona

1. **Inicio** – Carga `SiteConfig.BASE_URL` en el WebView oculto e inyecta `JS_CATALOG`
   (tarjetas agrupadas por sección) y `JS_DISCOVER` (detecta biblioteca, buscador y
   géneros en el menú del sitio).
2. **Biblioteca / Géneros** – Cuadrícula que va pidiendo la página siguiente
   (`JS_NEXT_PAGE`) o pulsa "Cargar más" (`JS_LOAD_MORE`) al llegar al final.
3. **Búsqueda** – Usa el formulario de búsqueda detectado (o `SEARCH_URL`) y también
   busca entre los animes que la app ya cargó.
4. **Ficha** – `JS_DETAILS` lee sinopsis, póster, géneros y episodios (`EPISODE_PATTERN`).
5. **Ver un episodio** – `WebPlayerActivity` abre la página del episodio tal cual, como un
   navegador, y su propio reproductor hace el trabajo:
   - Flechas mueven un cursor; **OK** hace clic donde está.
   - **Modo cine** (`JS_THEATER_ON`): el iframe/video más grande ocupa toda la pantalla.
     Se alterna con **Menú** o **Info**.
   - El botón de pantalla completa del reproductor funciona (`onShowCustomView`).
   - **CH+ / CH−** cambian de episodio; al avanzar, el anterior queda marcado como visto.
   - Se bloquean dominios de anuncios conocidos y las redirecciones a otros sitios.

> El WebView de lectura está **debajo** de la UI (tapado y sin foco). Se hace así en vez de
> `visibility="gone"` porque Chromium pausa o limita las páginas que no considera visibles.

`PlayerActivity` (ExoPlayer) solo se usa cuando la URL del episodio ya es un video directo
(`.m3u8`, `.mp4`, `.mkv`, `.webm`), por ejemplo desde tu propio servidor.

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
  conectado por ADB: verás los WebView de la app, su consola y su pestaña *Network*.
- En **Logcat** filtra por la etiqueta `Scraper`: muestra cuántos animes/episodios
  se leyeron, la biblioteca/buscador detectados y las navegaciones bloqueadas.

## Limitaciones

- Si el sitio cambia su HTML, hay que ajustar `SiteConfig.kt`.
- En el reproductor web la app no sabe el minuto exacto del video: guarda qué episodio
  abriste y lo marca como visto al pasar al siguiente o tras ~18 minutos.
- El modo cine elige el iframe/video más grande; si tapa un botón que necesitas
  (p. ej. elegir servidor), desactívalo con **Menú** o **Info**.

## Controles en el reproductor web

| Tecla                       | Acción                                              |
|-----------------------------|-----------------------------------------------------|
| ◀ ▲ ▼ ▶                     | Mueven el cursor (en el borde, desplazan la página) |
| OK / Centro                 | Clic donde está el cursor                           |
| Play/Pausa del control      | Clic en el centro de la pantalla                    |
| CH+ / CH− (Siguiente/Anterior) | Episodio siguiente / anterior                    |
| Menú / Info                 | Activa o desactiva el modo cine                     |
| Atrás                       | Sale de pantalla completa; si no, vuelve o cierra   |
