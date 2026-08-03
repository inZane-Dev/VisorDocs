import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Credenciales de firma, leidas de un archivo que NO esta en el repositorio.
 *
 * Antes cada version publicada se firmaba a mano con un keystore de usar y tirar, lo
 * que hacia imposible actualizar la app: Android solo acepta una actualizacion si viene
 * firmada con la misma clave que la version instalada.
 *
 * Si el archivo no existe —al clonar el repositorio, o en integracion continua— la
 * compilacion NO falla: `release` se queda sin firma de publicacion y usa la de
 * depuracion. Asi cualquiera puede compilar el proyecto sin tener la clave privada.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigningKey = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.visordocs"
    compileSdk = 36

    // androidx.pdf alpha19 exige compilar contra la SDK Extension 19; la imagen base
    // de API 36 se queda en la 18. Requiere el paquete "platforms;android-36-ext19".
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.visordocs"
        // minSdk 28 (Android 9): es el minimo que exige androidx.pdf.
        minSdk = 28
        targetSdk = 36
        // Ambos suben en cada publicacion. `versionCode` es el que mira Android para
        // decidir si un APK es mas nuevo que el instalado: si no sube, el sistema
        // rechaza la actualizacion. `versionName` es solo el texto que ve la persona.
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // v1 es la firma antigua, dentro del propio ZIP; con minSdk 28 ningun
                // dispositivo la necesita. v3 se pide explicitamente porque es la que
                // permite ROTAR la clave: si algun dia esta se filtra, se puede sustituir
                // sin que los telefonos rechacen la actualizacion por firma distinta.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 8 no genera BuildConfig salvo que se pida. Se necesita para que el
        // registro del intent entrante solo exista en compilaciones de depuracion.
        buildConfig = true
    }

    /**
     * Lint forma parte de la compilacion, no es opcional.
     *
     * Se aprendio por las malas: durante mucho tiempo no se ejecuto nunca, y escondia un
     * error real en el manifest (un MIME con mayusculas que no coincidia jamas).
     *
     * `warningsAsErrors` puede parecer excesivo, pero con la linea base los avisos que ya
     * existen quedan aceptados y solo revientan los NUEVOS. Es la unica forma de que no
     * se vuelvan a acumular sin que nadie los mire.
     */
    lint {
        abortOnError = true
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        // No detiene la compilacion por no poder consultar si hay versiones mas nuevas.
        disable += "AndroidGradlePluginVersion"

        /*
         * OldTargetApi avisa de que `targetSdk` no apunta a la version mas reciente de
         * Android. Se descarta porque su resultado depende de QUE SDK tenga instalada la
         * maquina que compila: en este equipo no salta y en el ejecutor de integracion
         * continua si, con el mismo codigo. Un aviso que aparece o no segun el ordenador
         * no puede detener la compilacion.
         *
         * Subir `targetSdk` no es cambiar un numero: altera comportamientos del sistema
         * que la app hereda, y toca hacerlo a proposito y probandolo, no porque el
         * ejecutor tenga una plataforma mas nueva descargada.
         */
        disable += "OldTargetApi"

        /*
         * TrustAllX509TrustManager: tres avisos dentro de BouncyCastle, que PDFBox
         * arrastra para poder abrir PDF cifrados. Contiene un gestor de certificados TLS
         * vacio, el patron clasico de "aceptar cualquier certificado".
         *
         * Se descarta porque en esta app ese codigo es inalcanzable: el manifest no
         * declara el permiso de Internet, asi que el proceso no puede abrir ninguna
         * conexion de red. No es codigo propio y no se usa; eliminarlo significaria
         * renunciar a los PDF con contrasena.
         *
         * Va aqui y no en la linea base a proposito. La linea base guarda la RUTA del
         * archivo donde aparece el aviso, y la de estos es el .jar dentro de la cache de
         * Gradle del equipo que la genero (".../Users/<usuario>/.gradle/caches/..."). En
         * otro ordenador o en integracion continua esa ruta no existe, el aviso deja de
         * coincidir y la compilacion se cae. Descartarlo por identificador es portable.
         */
        disable += "TrustAllX509TrustManager"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    // El BOM alinea las versiones de todas las librerias de Compose entre si.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Necesario para el tema Material3 que exige el fragment de androidx.pdf.
    implementation(libs.google.android.material)

    // AndroidFragment(): permite incrustar el PdfViewerFragment dentro de Compose.
    implementation(libs.androidx.fragment.compose)

    // ViewModel de la pantalla del visor: viewModel() y collectAsStateWithLifecycle().
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Orientacion EXIF de las fotos. Se usa la version de androidx y no la del
    // framework porque corrige fallos conocidos y reconoce mas formatos, HEIC incluido.
    implementation(libs.androidx.exifinterface)

    // Motor principal de PDF.
    implementation(libs.androidx.pdf.viewer.fragment)

    // Union de PDF. Android sabe leer PDF (PdfRenderer) y escribirlos desde cero
    // (PdfDocument), pero no copiar una pagina de un documento a otro: para eso hay que
    // manipular la estructura interna del formato, que es lo que hace PDFBox.
    implementation(libs.pdfbox.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pruebas en la JVM: convertidores que no dependen de Android (RTF, CSV, SVG) y
    // utilidades de lectura. Son funciones puras, asi que corren en milisegundos.
    testImplementation(libs.junit)

    // Pruebas en dispositivo: los convertidores de OOXML, OpenDocument y EPUB usan
    // `android.util.Xml`, que solo existe en Android. Llevarlos a la JVM exigiria
    // Robolectric o un parser alternativo; ejecutarlos en el emulador es mas fiel.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
