# Round 10 (Task 5): release keeps for the minified build.
#
# The sherpa-onnx AAR in app/libs ships an EMPTY consumer proguard file, and
# its Kotlin classes (com.k2fsa.sherpa.onnx.*) are looked up from the native
# TTS engine by exact class/field/method/constructor names. Without these
# rules R8 renames or strips members and native init fails at runtime even
# though the build succeeds.
-keep class com.k2fsa.** { *; }
-keepclasseswithmembernames class com.k2fsa.** {
    native <methods>;
}

# JNI-described classes keep their names end-to-end; annotations are read
# reflectively by Media3/serialization tooling.
-keepattributes *Annotation*
