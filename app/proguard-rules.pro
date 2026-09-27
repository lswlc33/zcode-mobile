# OkHttp ships its own consumer rules; these suppress the optional-platform
# warnings R8 emits for its Conscrypt/BouncyCastle references.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ZXing reads barcode formats by name from POSSIBLE_FORMATS hints.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# androidx.security:security-crypto brings in Tink, which is annotated with
# errorprone annotations that are compile-only and not on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
