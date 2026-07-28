# VisorDocs

Visor de documentos para Android. Fase 1: **PDF**, con integración en el menú
"Abrir con" del sistema (WhatsApp, Gmail, Archivos).

Kotlin + Jetpack Compose. Renderizado 100% en el dispositivo: ningún archivo sale
del teléfono. La app **no pide permisos de almacenamiento**.

---

## 1. Preparar el entorno (una sola vez)

En este equipo no había JDK ni Android SDK, así que hay que instalarlos.

1. Descargar e instalar **Android Studio**: https://developer.android.com/studio
   Trae su propio JDK 17, no hace falta instalar Java aparte.
2. Abrir Android Studio → **More Actions** → **SDK Manager** y marcar:
   - Pestaña *SDK Platforms*: **Android API 36**
   - Pestaña *SDK Tools*: **Android SDK Build-Tools 36**, **Android SDK Platform-Tools**
3. Añadir `platform-tools` al `PATH` de Windows para poder usar `adb`.
   Normalmente queda en:
   ```
   C:\Users\gerge\AppData\Local\Android\Sdk\platform-tools
   ```

## 2. Preparar el celular

1. Ajustes → **Acerca del teléfono** → tocar 7 veces en **Número de compilación**.
2. Ajustes → **Opciones de desarrollador** → activar **Depuración por USB**.
3. Conectar el cable USB y aceptar el diálogo *Permitir depuración USB* que aparece
   en el teléfono.
4. Comprobar desde una terminal:
   ```
   adb devices
   ```
   Debe listar el dispositivo como `device`. Si dice `unauthorized`, revisar el paso 3.

## 3. Abrir y compilar

1. Android Studio → **Open** → seleccionar la carpeta `VisorDocs`.
2. Esperar el *Gradle Sync*. La primera vez descarga Gradle y las dependencias
   (varios minutos).
3. Con el celular conectado, pulsar **Run** (▶) o desde terminal:
   ```
   gradlew.bat installDebug
   ```

> **Nota sobre el Gradle Wrapper:** el repositorio incluye
> `gradle/wrapper/gradle-wrapper.properties` pero no el `.jar` binario ni los scripts
> `gradlew`. Android Studio los regenera durante el primer sync. Si prefieres hacerlo
> a mano, desde la carpeta del proyecto:
> ```
> gradle wrapper --gradle-version 8.14.3
> ```

---

## Cómo está organizado

```
app/src/main/java/com/deiby/visordocs/
├── MainActivity.kt                  Única Activity. Traduce los Intent entrantes.
├── core/
│   ├── DocumentType.kt              Formatos + detección (MIME → extensión → magic bytes)
│   └── DocumentSource.kt            Nombre, tamaño y tipo de un Uri
├── ui/
│   ├── VisorDocsApp.kt              Navegación: inicio ↔ visor
│   ├── home/HomeScreen.kt           Botón "Abrir documento" (selector del sistema)
│   ├── viewer/ViewerScreen.kt       Elige el visor según el formato  ← punto de extensión
│   ├── components/StatusViews.kt    Estados de carga y de error
│   └── theme/Theme.kt               Material 3, claro/oscuro, Material You
└── viewer/pdf/
    ├── PdfEngineSupport.kt          Decide qué motor de PDF admite el dispositivo
    ├── JetpackPdfViewer.kt          Motor principal (androidx.pdf)
    ├── LegacyPdfViewer.kt           Motor de respaldo (UI)
    └── LegacyPdfDocument.kt         Motor de respaldo (PdfRenderer del sistema)
```

### Los dos motores de PDF

`androidx.pdf` es la librería oficial de Google y aporta zoom, scroll continuo,
**búsqueda y selección de texto**, hipervínculos y PDF con contraseña. Pero se apoya
en un módulo actualizable del sistema (SDK Extension) que no todos los teléfonos
tienen.

Por eso `PdfEngineSupport` lo comprueba **en tiempo de ejecución** y, si falta, usa un
visor propio sobre `android.graphics.pdf.PdfRenderer`, que funciona en cualquier
dispositivo pero solo muestra imágenes de página (sin búsqueda ni selección de texto).

Qué motor quedó activo se ve de dos formas:

- Al pie de la pantalla de inicio: *Motor de PDF: JETPACK* o *LEGACY*.
- En logcat: `adb logcat -s VisorDocs`

Para forzar el motor de respaldo y compararlos, cambiar `FORCE_LEGACY_ENGINE = true`
en `PdfEngineSupport.kt`.

### Detección de formato

No se confía solo en el MIME type porque WhatsApp y varios gestores de archivos
entregan todo como `application/octet-stream`. `DocumentTypeDetector` prueba en
cascada: MIME del intent → MIME del ContentProvider → extensión del nombre real →
primeros bytes del archivo.

---

## Cómo verificar que funciona

| # | Prueba | Resultado esperado |
|---|---|---|
| 1 | `gradlew.bat assembleDebug` | Compila sin errores |
| 2 | `gradlew.bat installDebug` | La app aparece en el lanzador |
| 3 | Abrir la app | Se ve el motor de PDF activo al pie |
| 4 | "Abrir documento" → elegir un PDF | Se visualiza; funciona el zoom con dos dedos y el scroll |
| 5 | Enviarse un PDF por WhatsApp y tocarlo | VisorDocs aparece en "Abrir con" y lo abre |
| 6 | Lo mismo desde Gmail y desde Archivos | Igual que el punto 5 |
| 7 | Abrir un `.docx` desde el selector | Mensaje "aún no disponible", sin cierre inesperado |
| 8 | PDF de más de 100 páginas | Scroll fluido, sin quedarse sin memoria |
| 9 | Girar la pantalla | Mantiene el documento abierto |

---

## Si el Gradle Sync falla

Las versiones están fijadas en [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
y se verificaron contra los repositorios Maven en julio de 2026. Si alguna ha
quedado obsoleta, el error de Gradle indica cuál y basta con cambiar ese número.

| Síntoma | Qué revisar |
|---|---|
| `Plugin ... was not found` | La versión de `agp` o `kotlin` en el catálogo |
| `Could not find androidx.pdf:...` | `pdfViewerFragment`: sigue en alpha y cambia con frecuencia |
| `Minimum supported Gradle version is X` | `distributionUrl` en `gradle/wrapper/gradle-wrapper.properties` |
| `Unresolved reference: documentUri` | La API alpha de androidx.pdf cambió; ver `JetpackPdfViewer.kt` |

Se usa **AGP 8.13.2** a propósito y no la línea 9.x: AGP 9 introduce un DSL nuevo y
Kotlin integrado, que exigirían otra configuración. Cuando se quiera migrar, usar el
*AGP Upgrade Assistant* de Android Studio.

---

## Siguientes fases

El `when (document.type)` de `ViewerScreen.kt` es el único punto que hay que ampliar.
`DocumentType` ya reconoce estos formatos y muestra "aún no disponible":

- **Fase 2 — `.docx`**: es un ZIP con XML. Convertir `word/document.xml` a HTML y
  mostrarlo en un WebView local.
- **Fase 3 — `.xlsx`**: lectura en streaming y render como tabla, con selector de hojas.
- **Fase 4 — `.pptx`**: diapositivas como aproximación (texto, imágenes, posiciones).
- **Fase 5**: `txt`, `csv`, `md` e imágenes. Bajo costo.

Los formatos binarios antiguos (`.doc`, `.xls`, `.ppt`) quedan fuera del alcance actual.
