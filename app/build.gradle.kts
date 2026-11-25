import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.play.publisher)
}

// Apply Firebase Google Services & Crashlytics plugins only when gitignored google-services.json
// is present locally so public GitHub builds succeed without Firebase credentials.
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
  apply(plugin = "com.google.firebase.crashlytics")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { load(it) }
  }
}

android {
  namespace = "com.doorking.gate"
  compileSdk {
    version = release(37) {
      minorApiLevel = 1
    }
  }

  defaultConfig {
    applicationId = "com.doorking.gate"
    minSdk = 34
    targetSdk = 37
    versionCode = 32
    versionName = "1.0.31"

    val defaultServerUrl = keystoreProps.getProperty("defaultServerUrl")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: "https://gate.example.com"
    buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (keystorePropsFile.exists() && keystoreProps.getProperty("storeFile") != null) {
      create("release") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      optimization {
        enable = true
      }
      signingConfigs.findByName("release")?.let { signingConfig = it }
    }
    debug {
      isMinifyEnabled = false
    }
    create("benchmark") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += listOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/DebugProbesKt.bin",
        "/META-INF/androidx/**/LICENSE.txt",
        "/kotlin/**/*.kotlin_builtins",
        "/*.proto",
      )
    }
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

play {
  val credsPath = keystoreProps.getProperty("playServiceAccountCredentials")?.trim()
    ?: System.getenv("PLAY_SERVICE_ACCOUNT_CREDENTIALS")?.trim()
  if (!credsPath.isNullOrBlank()) {
    serviceAccountCredentials.set(file(credsPath))
  }
  track.set((findProperty("playTrack") as? String)?.trim()?.ifBlank { null } ?: "internal")
  fromTrack.set("internal")
  promoteTrack.set("alpha")
  defaultToAppBundles.set(true)
  releaseStatus.set(ReleaseStatus.COMPLETED)
  resolutionStrategy.set(ResolutionStrategy.AUTO)
}

val playSyncCreds = keystoreProps.getProperty("playServiceAccountCredentials")?.trim()
  ?: System.getenv("PLAY_SERVICE_ACCOUNT_CREDENTIALS")?.trim()
val playSyncScript = keystoreProps.getProperty("playUniversalApkSyncScript")?.trim()
  ?: System.getenv("PLAY_UNIVERSAL_APK_SYNC_SCRIPT")?.trim()
val playSyncOutput = keystoreProps.getProperty("playUniversalApkOutput")?.trim()
  ?: System.getenv("PLAY_UNIVERSAL_APK_OUTPUT")?.trim()
val playSyncConfigured = !playSyncCreds.isNullOrBlank() &&
  !playSyncScript.isNullOrBlank() &&
  !playSyncOutput.isNullOrBlank() &&
  file(playSyncScript).exists()

val syncPlayUniversalApk = tasks.register<Exec>("syncPlayUniversalApk") {
  group = "publishing"
  description = "Downloads Google Play's Play-signed universal APK for this versionCode to playUniversalApkOutput (configured in keystore.properties)."
  val pkgName = android.defaultConfig.applicationId ?: "com.doorking.gate"
  val verCode = (android.defaultConfig.versionCode ?: 1).toString()

  enabled = playSyncConfigured
  if (playSyncConfigured) {
    commandLine(
      "node", playSyncScript!!,
      "--credentials", playSyncCreds!!,
      "--package", pkgName,
      "--version-code", verCode,
      "--output", playSyncOutput!!,
    )
  }
}

tasks.matching { it.name == "publishReleaseBundle" }.configureEach {
  finalizedBy(syncPlayUniversalApk)
}

// Ensure Play edit is committed before downloading the Play-signed universal APK.
syncPlayUniversalApk.configure {
  mustRunAfter(rootProject.tasks.matching { it.name.startsWith("commitEditFor") })
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android & Coroutines
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.kotlinx.coroutines.android)

  // Compose Material 3
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Navigation 3 & Predictive Back
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.navigationevent.compose)

  // Auth (Credential Manager Passkeys, Google ID, Biometric Fingerprint Unlock & Chrome Custom Tabs SSO)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.googleid)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.browser)

  // Google Play In-App Updates (Flexible updates on Internal / Alpha / Production)
  implementation(libs.play.app.update)
  implementation(libs.play.app.update.ktx)

  // Android Auto (IoT category — resident Buzz Me In on car display)
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.app.projected)

  // Android AppFunctions (Gemini & on-device agent voice commands on Android 16+)
  implementation(libs.androidx.appfunctions)
  ksp(libs.androidx.appfunctions.compiler)

  // Firebase Cloud Messaging (Push Notifications) & Crashlytics (no Analytics)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)
  implementation(libs.firebase.crashlytics)

  // Networking, Serialization, Persistence & Media
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
  implementation(libs.retrofit)
  implementation(libs.retrofit.kotlinx.serialization)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  debugImplementation(libs.androidx.glance.appwidget.preview)
  debugImplementation(libs.androidx.glance.preview)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.datasource.okhttp)

  // Local Unit Tests
  testImplementation(libs.junit)
  testImplementation(libs.androidx.car.app.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.turbine)

  // Instrumented & Compose UI Tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
