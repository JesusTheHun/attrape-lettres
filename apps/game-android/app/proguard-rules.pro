# R8 rules for the release build.
#
# kotlinx.serialization generates a `Companion.serializer()` per @Serializable
# class and looks it up reflectively for the polymorphic/contextual paths. R8
# understands the common cases through the shrinker rules shipped with the
# library, but the generated serializer objects still need keeping when the
# class itself survives only through a serializer reference.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
