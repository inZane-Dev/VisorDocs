# androidx.pdf carga clases por reflexion desde el proceso aislado de renderizado.
# Sin estas reglas, la compilacion release rompe el visor aunque el debug funcione.
-keep class androidx.pdf.** { *; }
-keep class android.graphics.pdf.** { *; }

# Los fragments se instancian por nombre de clase.
-keep public class * extends androidx.fragment.app.Fragment

# PDFBox resuelve por reflexion y por nombre buena parte de su maquinaria: filtros de
# compresion, manejadores de fuentes y los recursos que carga desde los assets. Con R8
# recortando, la union de PDF fallaba en release aunque funcionase en debug.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**

# PDFBox arrastra referencias a clases de Java de escritorio (AWT, Swing, ImageIO) que en
# Android no existen. No se usan en los caminos que recorre la app, pero R8 avisa de
# ellas si no se le indica que las ignore.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.**
-dontwarn org.apache.**
