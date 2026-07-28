import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.deiby.visordocs"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deiby.visordocs"
        // minSdk 28 (Android 9): es el minimo que exige androidx.pdf.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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

    // Motor principal de PDF.
    implementation(libs.androidx.pdf.viewer.fragment)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
