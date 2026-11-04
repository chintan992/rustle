import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

// Round 10 (Task 5): optional release signing, read from a gitignored
// properties file in android_app/work/. When the file is absent (fresh clone,
// CI) assembleRelease produces an unsigned APK instead of failing the build.
val releaseKeystoreProperties = Properties().apply {
  val propsFile = rootProject.file("work/release-keystore.properties")
  if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseKeystoreFile: File? = releaseKeystoreProperties.getProperty("storeFile")?.let { raw ->
  // Relative storeFile values resolve against android_app/ (the gradle root),
  // i.e. "work/release.jks" -> android_app/work/release.jks.
  val f = File(raw)
  if (f.isAbsolute) f else rootDir.resolve(raw)
}

android {
    namespace = "com.chint.audiobook"
    compileSdk = 36
    defaultConfig {
        // Rustle — the store identity. applicationId is immutable after first
        // Play release; the code namespace above stays com.chint.audiobook
        // (they may differ; renaming the namespace would churn ~100 files
        // for zero store benefit).
        applicationId = "dev.chintan992.rustle"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Keep the release artifact broadly compatible; release overrides this
        // default filter below with all ABIs supported by the native AARs.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
      if (releaseKeystoreProperties.getProperty("storeFile") != null && releaseKeystoreFile?.exists() == true) {
        create("release") {
          storeFile = releaseKeystoreFile
          storeType = releaseKeystoreProperties.getProperty("storeType") ?: "PKCS12"
          storePassword = releaseKeystoreProperties.getProperty("storePassword")
          keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
          keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
        }
      }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
        release {
            ndk {
                abiFilters.clear()
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            // Round 10 (Task 5): ship minified. The sherpa-onnx AAR ships an
            // EMPTY consumer proguard file, so all its keeps live in
            // app/proguard-rules.pro (com.k2fsa.** reached from JNI).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystoreProperties.getProperty("storeFile") != null && releaseKeystoreFile?.exists() == true) {
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
      aidl = false
      // Round 10 (Task 3): BuildConfig.DEBUG gates the debug-only pack
      // fault-injection hooks (ModelManager debug* + Model screen card).
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests {
        // The sherpa-onnx engine path calls android.util.Log; return defaults
        // in JVM unit tests instead of throwing "not mocked".
        isReturnDefaultValues = true
      }
    }
}

kotlin {
    jvmToolchain(17)
}

configurations.all {
    resolutionStrategy {
        force("androidx.compose.foundation:foundation-android:1.10.6")
        force("androidx.compose.foundation:foundation-layout-android:1.10.6")
        force("androidx.compose.material3:material3-android:1.4.0")
        force("androidx.compose.material:material-icons-core-android:1.7.8")
    }
    // androidx.compose.ui:ui-tooling (debugImplementation) transitively drags in
    // the 2020-era Compose M2 android androidx.compose.material:material:1.0.0,
    // whose material-icons-core:1.0.0 declares the SAME manifest namespace
    // (androidx.compose.material.icons) as the forced modern icons artifact,
    // which fails the manifest merge. The app is M3-only and uses the modern
    // icons artifact exclusively, so only the ancient icons module is dropped.
    exclude(group = "androidx.compose.material", module = "material-icons-core")
}

dependencies {
  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose - explicit versions, no BOM
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3) {
    exclude(group = "androidx.compose.foundation", module = "foundation")
    exclude(group = "androidx.compose.foundation", module = "foundation-layout")
  }
  implementation(libs.androidx.compose.foundation) {
    exclude(group = "androidx.compose.foundation", module = "foundation")
    exclude(group = "androidx.compose.foundation", module = "foundation-layout")
  }
  implementation(libs.androidx.compose.material.icons.core)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Serialization, DataStore, Networking, Media3 & TTS
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.okhttp)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)

  // Firebase (Analytics + Crashlytics): BOM-pinned; config file
  // app/google-services.json (placeholder committed — swap for the real one).
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.crashlytics)
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
