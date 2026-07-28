// Configuracion raiz. Los plugins se declaran aqui sin aplicarlos ("apply false")
// para que las versiones queden centralizadas en gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
