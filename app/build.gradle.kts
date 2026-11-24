import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.play.publisher)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { load(it) }
  }
}

android {
  namespace = "com.doorking.gate"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.doorking.gate"
    minSdk = 34
    targetSdk = 37
    versionCode = 17
    versionName = "1.0.16"

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
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfigs.findByName("release")?.let { signingConfig = it }
    }
    debug {
      isMinifyEnabled = false
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
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
  track.set("internal")
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

  // Auth (Credential Manager Passkeys & Chrome Custom Tabs SSO)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.androidx.browser)

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
