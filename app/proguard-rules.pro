# kotlinx.serialization – DTO třídy z :core
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class cz.fenixen.navigation.core.** {
    *** Companion;
}
-keepclasseswithmembers class cz.fenixen.navigation.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp volitelné závislosti
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
