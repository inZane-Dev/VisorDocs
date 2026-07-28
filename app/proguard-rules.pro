# androidx.pdf carga clases por reflexion desde el proceso aislado de renderizado.
# Sin estas reglas, la compilacion release rompe el visor aunque el debug funcione.
-keep class androidx.pdf.** { *; }
-keep class android.graphics.pdf.** { *; }

# Los fragments se instancian por nombre de clase.
-keep public class * extends androidx.fragment.app.Fragment
