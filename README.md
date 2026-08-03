# VisorDocs

Visor de documentos para Android, con integración en el menú "Abrir con" del sistema
(WhatsApp, Gmail, Archivos).

| Formato | Estado |
|---|---|
| PDF | Zoom, scroll continuo, búsqueda y selección de texto, **unir varios en uno** |
| `.docx` `.docm` | Encabezados, negrita/cursiva/subrayado/tachado, listas, tablas, alineación |
| `.xlsx` `.xlsm` | Todas las hojas, cadenas compartidas, numeración de filas fiel |
| `.pptx` `.pptm` | Una tarjeta por diapositiva, en el orden de la presentación |
| `.odt` `.ods` `.odp` | LibreOffice/OpenOffice, con su tabla de estilos |
| `.rtf` | Formato, acentos (escape ANSI y Unicode), comillas tipográficas |
| `.epub` | Capítulos en el orden del *spine*, XHTML saneado por lista blanca |
| `.csv` `.tsv` | Tabla, con separador autodetectado y comillas del formato |
| `.html` `.htm` | Se renderiza **como página**, con sus propios estilos |
| Texto y código | `.txt` `.md` `.log` `.json` `.yaml` `.ini` `.kt` `.py` `.js` `.sql`… |
| **Sin extensión** | Se identifica por el contenido: texto, HTML, RTF o SVG |
| Imágenes | JPG, PNG, WebP, GIF, BMP, **HEIC/HEIF/AVIF**, con zoom |
| `.svg` | Vectorial, dibujado por el WebView |
| `.doc` `.xls` `.ppt` | **No**: son OLE2, fuera del alcance (ver más abajo) |

Kotlin + Jetpack Compose, **sin dependencias externas para Office**: los formatos OOXML
son ZIP con XML, y se leen con `ZipInputStream` y `XmlPullParser` del propio sistema.

Renderizado 100% en el dispositivo: ningún archivo sale del teléfono. La app **no pide
permisos de almacenamiento**.

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
   %LOCALAPPDATA%\Android\Sdk\platform-tools
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

> **Nota sobre el Gradle Wrapper:** el repositorio lo incluye completo (los scripts
> `gradlew`/`gradlew.bat` y el `.jar`), así que no hace falta tener Gradle instalado:
> el propio wrapper descarga la versión correcta la primera vez.

---

## Arquitectura

Dos capas, `data` y `ui`, con un paquete `model` compartido entre ambas. Es la
arquitectura recomendada de Android sin la capa de dominio, que la propia guía marca
como opcional y aquí no aportaría nada: hay una sola pantalla con estado y ninguna
regla de negocio que se reutilice entre varios ViewModel.

```
app/src/main/java/com/visordocs/
├── MainActivity.kt                 Traduce los Intent entrantes a un DocumentRequest
│
├── model/                          Datos compartidos. Kotlin puro, sin Android.
│   ├── DocumentType.kt             Los formatos que la app reconoce
│   ├── DocumentSource.kt           DocumentRequest (qué abrir) y DocumentSource (identificado)
│   └── DocumentContent.kt          Pdf | Markup | WebPage | PlainText | Picture | …
│
├── data/                           Todo lo que toca el sistema de archivos
│   ├── DocumentRepository.kt       ← ÚNICO punto que habla con el ContentResolver
│   ├── DocumentTypeDetector.kt     Cascada MIME → provider → extensión → magic bytes
│   ├── TextSniffer.kt              Último recurso: ¿esto es texto? (archivos sin extensión)
│   ├── zip/ZipPackage.kt           Lector de ZIP con límites contra ZIP bombas
│   ├── xml/XmlSupport.kt           Utilidades de XmlPullParser
│   ├── ooxml/OoxmlRelationships.kt Resolución de `rId` → ruta de la parte
│   ├── markup/                     Formato → fragmento HTML. Kotlin puro y testeable.
│   │   ├── Markup.kt               Resultado (cuerpo + truncado) y etiquetas
│   │   ├── DocxMarkup.kt · XlsxMarkup.kt · PptxMarkup.kt
│   │   ├── OdtMarkup.kt · OdsMarkup.kt · OdpMarkup.kt · OdfStyles.kt
│   │   ├── EpubMarkup.kt · RtfMarkup.kt · CsvMarkup.kt · SvgMarkup.kt
│   │   ├── WordNumbering.kt        Viñeta o número: la cadena de numbering.xml
│   │   ├── ExcelNumberFormats.kt   Números de serie → fechas, con el fallo de 1900
│   │   └── EmbeddedImages.kt       Imágenes del documento → data: URI, con presupuesto
│   └── source/
│       ├── TextFileReader.kt       Texto plano con tope de tamaño
│       └── ImageDecoder.kt         Submuestreo y orientación EXIF
│
└── ui/
    ├── VisorDocsApp.kt             Navegación: inicio ↔ visor
    ├── theme/Theme.kt              Material 3, claro/oscuro, Material You
    ├── common/StatusViews.kt       Carga, error y formato de tamaños
    ├── home/HomeScreen.kt          Botón "Abrir documento" (selector del sistema)
    └── viewer/
        ├── ViewerViewModel.kt      Estado de la pantalla: una sola máquina de estados
        ├── ViewerScreen.kt         Elige el renderizador según la forma del contenido
        └── render/                 Solo pintan lo que reciben; no cargan nada
            ├── HtmlDocument.kt     Envoltorio HTML + colores del tema
            ├── MarkupViewer.kt     WebView aislado (sin JS, sin red, sin navegación)
            ├── WebViewWarmup.kt    Arranca el motor antes de que haga falta
            ├── PlainTextViewer.kt · PictureViewer.kt
            └── pdf/                Los dos motores de PDF
```

### Por qué así

- **El `ContentResolver` vive en un solo sitio.** Antes cuatro archivos lo abrían por
  su cuenta. Ahora solo `DocumentRepository`, y el resto del código trabaja con datos
  normales — lo que además lo hace testeable sin un dispositivo.
- **Una sola máquina de estados.** Cada visor repetía su propio
  `cargando / error / listo` con su `produceState`. Ahora hay una en el ViewModel y los
  renderizadores son tontos.
- **El parseo no conoce los colores.** Los convertidores producen solo el *cuerpo* del
  HTML; el tema se aplica al pintar. Antes un cambio a modo oscuro volvía a analizar
  el archivo entero.
- **`DocumentContent` se organiza por *cómo se pinta*, no por formato.** Un `.docx`, un
  `.xlsx` y un `.csv` son los tres `Markup`, así que la interfaz conoce cuatro formas
  de pintar en lugar de once formatos.

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

### Unir varios PDF en uno

Con un PDF abierto, el botón **+** de la barra añade otros a la cola y el de guardar
escribe el resultado donde el usuario elija con el selector del sistema. El documento
abierto va primero.

La cola se ve entera, con el nombre de cada documento y su posición final. Cada uno se
puede **quitar por separado** o **subir y bajar** hasta dejar el orden que toca. Antes
era una sola línea con el número de documentos, y equivocarse en el tercero obligaba a
descartar los tres y volver a empezar.

También se puede seleccionar **varios PDF a la vez y compartirlos** a VisorDocs
(`ACTION_SEND_MULTIPLE`) desde Archivos, Drive o cualquier gestor: se abre el primero y
el resto queda encolado, listo para guardar.

Android no puede hacer esto por su cuenta: `PdfRenderer` lee y `PdfDocument` escribe
páginas nuevas, pero no existe forma de copiar una página de un documento a otro. La
alternativa sin librerías sería rasterizar cada página a imagen, y el resultado perdería
el texto —nada de buscar ni seleccionar— y pesaría mucho más.

Por eso se usa **PDFBox**, que trabaja sobre la estructura interna del formato: la unión
conserva el texto, los enlaces y la calidad vectorial. Cuesta unos 12 MB de APK y arrastra
BouncyCastle, que es lo que permite abrir PDF cifrados.

> BouncyCastle contiene un gestor de certificados TLS vacío que Lint marca como error de
> seguridad. Está aceptado en la línea base porque en esta app ese código es inalcanzable:
> el manifest **no declara el permiso de Internet**, así que el proceso no puede abrir
> ninguna conexión. Ver la nota en `app/build.gradle.kts`.

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
| 7 | Abrir un `.docx` con formato | Encabezados, negritas, listas y tablas |
| 8 | Abrir un `.xlsx` de varias hojas | Una tabla por hoja, numeración igual que en Excel |
| 9 | Abrir un `.pptx` | Una tarjeta por diapositiva, en orden |
| 10 | Abrir un `.doc` antiguo | Mensaje que sugiere convertirlo, sin cierre inesperado |
| 11 | PDF de más de 100 páginas | Scroll fluido, sin quedarse sin memoria |
| 12 | Girar la pantalla | Mantiene el documento abierto |

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

## Cómo se leen los formatos de Office

Un `.docx`, `.xlsx` o `.pptx` es un ZIP que contiene XML. No hace falta ninguna
librería: `OoxmlPackage` abre el ZIP y los tres convertidores recorren el XML con
`XmlPullParser` y producen HTML, que se muestra en un WebView usado solo como motor
de maquetación — con JavaScript desactivado, sin acceso a archivos y sin poder navegar
a ningún sitio.

`OoxmlPackage` sólo conserva las partes `.xml` y `.rels`, y aplica tres límites
(número de entradas, tamaño por entrada y tamaño total) para que un ZIP manipulado no
pueda agotar la memoria.

### Lo que sí se reproduce, y costó

- **Imágenes incrustadas**: se convierten a `data:` URI y viajan dentro del propio HTML.
  Es lo único compatible con un WebView que tiene el acceso a archivos cerrado. El precio
  es memoria —base64 infla un 33 % y el `String` en UTF-16 vuelve a duplicar—, así que hay
  tope por imagen y presupuesto total para el documento.
- **Numeración real de las listas**: el párrafo solo dice a qué lista pertenece; el
  aspecto vive en `numbering.xml`, y no directo, sino a través de una definición abstracta.
  Sin recorrer esa cadena todo salía como viñeta.
- **Fechas de Excel**: Excel no guarda fechas, guarda números; lo que las convierte en
  fecha es el formato de la celda, que está en `styles.xml`. El calendario además viene
  partido en dos por un fallo histórico: Excel cree en un 29 de febrero de 1900 que nunca
  existió, así que el origen es distinto antes y después de esa fecha.

### Lo que sigue sin reproducirse

Es un visor, no un editor. Deliberadamente fuera: **colores, fuentes y posiciones**
concretas; en `.pptx` y `.odp`, la diapositiva se aproxima como texto y las imágenes van
al final de la tarjeta, no en su posición original.

### Los formatos binarios antiguos

`.doc`, `.xls` y `.ppt` **no** son ZIP: son contenedores OLE2, un sistema de archivos
completo dentro de un archivo, con estructuras propias de los años 90. No comparten
nada con sus equivalentes modernos y soportarlos sería un proyecto aparte. Tienen
entrada propia en `DocumentType` para poder decirlo con claridad y sugerir la
conversión, en vez de dar un error genérico.

## Pruebas

```
gradlew.bat testDebugUnitTest          # 71 pruebas en la JVM, segundos
gradlew.bat connectedDebugAndroidTest  # 79 pruebas en dispositivo
gradlew.bat lintDebug                  # sin avisos nuevos
```

Las dos primeras y el lint se ejecutan solas en cada empujón: ver
[`.github/workflows/ci.yml`](.github/workflows/ci.yml). Las instrumentadas se quedan
fuera de la integración continua porque exigen arrancar un emulador, que multiplica por
varios el tiempo de cada ejecución; se lanzan a mano antes de publicar.

Lint **forma parte de la compilación**: `abortOnError` y `warningsAsErrors` están
activos, con una línea base que acepta los avisos que ya existían. Cualquier aviso nuevo
rompe el build. Se puso porque durante mucho tiempo no se ejecutó nunca, y escondía un
error real en el manifest.

Están partidas en dos conjuntos por una razón concreta: los convertidores de OOXML,
OpenDocument y EPUB usan `android.util.Xml`, que **solo existe en Android**. Llevarlos a
la JVM exigiría Robolectric o un parser alternativo; ejecutarlos en el emulador es más
fiel y no añade dependencias.

En `src/test` va lo que es Kotlin puro: RTF, CSV, SVG, el escapado de HTML, la lectura de
texto y el lector de ZIP. En `src/androidTest`, todo lo que pasa por XML, la unión de PDF
y la cascada de identificación de formato — esta última necesita un `ContentResolver`, y
se le pasan archivos reales escritos en la caché en lugar de un doble de pruebas, para
que se ejercite el camino de lectura de verdad.

Varias pruebas son **regresiones de fallos reales** encontrados durante el desarrollo, y
lo dicen en su nombre: el formato de Word que se derramaba al siguiente tramo de texto,
las filas de Excel que se desplazaban al colapsar los huecos, y las repeticiones de ODS
que expandidas a ciegas generarían millones de celdas.

## Seguridad

- **Cero permisos.** El manifest no declara ninguno; se trabaja solo con los `content://`
  que conceden otras apps o el selector del sistema.
- **Sin red.** No hay permiso de Internet y el WebView bloquea toda petición de recursos
  con `shouldInterceptRequest`, además de la navegación.
- **WebView cerrado**: sin JavaScript, sin acceso a archivos ni a content providers, y
  cargado con `baseUrl` nulo para que quede en un origen opaco.
- **Todo el texto se escapa** antes de entrar en el HTML. La única excepción deliberada
  es el SVG, que debe incrustarse en crudo para dibujarse; lo que lo contiene es el
  WebView, no una limpieza previa.
- **EPUB por lista blanca**: solo se reemiten etiquetas estructurales conocidas, sin
  atributos. Scripts, estilos del libro y referencias externas no llegan a la salida.
- **Sin copia de seguridad** (`allowBackup="false"`): la app no guarda datos y las copias
  temporales de PDF en caché no deberían salir del dispositivo.
- **El registro de intents solo existe en depuración.** La ruta de un URI suele incluir
  el nombre del archivo, que puede ser información personal.

## Cómo añadir un formato nuevo

Tres pasos, y en el caso habitual **la interfaz no se toca**:

1. Añadir el valor a `model/DocumentType.kt` con su etiqueta en `strings.xml`.
2. Reconocerlo en `data/DocumentTypeDetector.kt` (MIME y/o extensión y/o magic bytes).
3. Añadir su rama en el `when` de `DocumentRepository.load()`, devolviendo el
   `DocumentContent` que corresponda.

Si el formato se puede convertir a HTML, texto o mapa de bits, ahí termina: ya hay
renderizador. Solo hay que tocar `ui/viewer/` si trae una forma de pintar que no
existe todavía — y entonces se añade una variante a `DocumentContent` y su rama en
`ViewerScreen`.

## Publicar una versión

El `release` se firma con una clave propia, leída de `keystore.properties`, que **no está
en el repositorio** (ni el `.keystore`): quien tenga esos dos archivos puede publicar
actualizaciones que el sistema aceptaría como legítimas.

Si el archivo no existe —al clonar el proyecto, o en integración continua— la compilación
**no falla**: el `release` se firma con la clave de depuración. Así cualquiera puede
compilar sin tener la clave privada.

Para publicar hay que subir **los dos** números de `defaultConfig`: `versionCode`, que es
el que mira Android para saber si un APK es más nuevo que el instalado, y `versionName`,
que es el texto que ve la persona.

```
gradlew.bat assembleRelease
```

## Pendiente

- Los binarios **`.doc`/`.xls`/`.ppt`** quedan fuera del alcance (ver arriba).
- **Solo está en español.** `values/strings.xml` es el idioma por omisión, así que un
  teléfono en inglés también lo verá en español.
- **Sin historial de recientes.** Abrir, cerrar y volver obliga a buscar el archivo otra
  vez. No es un añadido menor: `retainAccess` está escrito a propósito para conservar
  **un solo** permiso persistente, y unos recientes de verdad obligarían a rehacerlo
  respetando el límite que impone el sistema.
- **Sin imprimir ni compartir** el documento abierto.
- **Buscar texto solo en PDF** con el motor de Jetpack. Los formatos que pasan por el
  WebView no tienen búsqueda, aunque `findAllAsync` estaría disponible.

### Cómo sobrevive el documento a la muerte del proceso

Android puede matar la app en segundo plano en cualquier momento. Para que al volver siga
abierto el mismo documento hacen falta dos cosas, y una sin la otra no sirve:

1. **La petición se guarda** con `rememberSaveable`, así que se restaura con la Activity.
2. **El acceso al archivo se conserva** con un permiso persistente sobre el `content://`.
   Sin él, el URI restaurado ya no sería legible y el visor solo podría mostrar un error.

El segundo punto tiene truco: pedir el permiso y no soltarlo nunca acumula una concesión
por archivo abierto, y el sistema limita cuántas puede tener una app —al pasarse, lanza
excepción—. Por eso `retainAccess` suelta las anteriores cada vez: siempre hay una.

Los URI que llegan por "Abrir con" desde mensajería no admiten permiso persistente; ahí
el documento se abre igual, pero no sobrevivirá a la muerte del proceso.

### Archivos sin extensión

`TextSniffer` mira los primeros 1024 bytes y decide si son texto. El criterio es
**conservador**: un solo byte cero o de control descarta el archivo, porque mostrar un
binario como texto llenaría la pantalla de símbolos sin sentido.

El detalle que importa está en la validación UTF-8: se decodifica con
`endOfInput = false`, de modo que una secuencia multibyte partida por el corte de la
muestra se entiende como "faltan datos" y no como error. Recortar unos bytes del final
"por si acaso" —que fue el primer intento— dejaba pasar binarios sin más que amputarles
la parte que los delataba. Lo detectó un test.

Si es texto, se mira el principio para distinguir RTF, HTML y SVG; si no encaja ninguno,
texto plano.

### Notas sobre los formatos nuevos

- **`.ods`** comprime de una forma que hay que tener en cuenta: repite filas y columnas
  con `table:number-rows-repeated`. Una hoja con una sola celda puede declarar 16382
  columnas vacías repetidas hasta el final, así que las repeticiones solo se expanden
  cuando la celda tiene contenido.
- **`.epub`** pasa cada capítulo por una **lista blanca** de etiquetas: lo que no está
  reconocido se descarta, con todos sus atributos. Así no llegan al WebView ni scripts,
  ni las hojas de estilo del libro, ni referencias a servidores.
- **`.svg` y `.html`** se entregan al WebView tal cual, sin sanear — es la única forma de
  que se vean como lo que son. Lo que los contiene es el WebView: sin JavaScript, sin
  acceso a archivos, origen opaco, y con `shouldInterceptRequest` cortando **toda**
  petición de recursos. Comprobado con fixtures que llevan `<script>` y una imagen
  remota a propósito: el script no se ejecuta y la imagen no llega a pedirse.
- **`.avif`** solo lo decodifica Android 12 o superior; en versiones anteriores el visor
  avisa de que no se pudo abrir en lugar de fallar en silencio.
- **`.rtf`** conserva formato y acentos, pero no imágenes ni la cuadrícula de las tablas.

## Licencia

[MIT](LICENSE). Puedes usarlo, modificarlo, distribuirlo e incluso venderlo. La única
condición es conservar el aviso de autoría y el texto de la licencia en las copias.
