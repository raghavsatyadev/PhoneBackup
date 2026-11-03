plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

android {
  namespace = libs.versions.nameSpace.get()
  compileSdk { version = release(libs.versions.compileSdk.get().toInt()) { minorApiLevel = 1 } }
  buildToolsVersion = libs.versions.buildTools.get()

  defaultConfig {
    applicationId = libs.versions.appId.get()
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = libs.versions.versionCode.get().toInt()
    versionName = libs.versions.versionName.get()
    resValue("string", "app_name", libs.versions.appName.get())
  }
  // Release key lives outside the repo; its path and passwords come from ~/.gradle/gradle.properties.
  val storeFilePath = providers.gradleProperty("PHONEBACKUP_STORE_FILE").orNull
  if (storeFilePath != null) {
    signingConfigs {
      create("release") {
        storeFile = file(storeFilePath)
        storePassword = providers.gradleProperty("PHONEBACKUP_STORE_PASSWORD").get()
        keyAlias = providers.gradleProperty("PHONEBACKUP_KEY_ALIAS").get()
        keyPassword = providers.gradleProperty("PHONEBACKUP_KEY_PASSWORD").get()
      }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }
  buildFeatures { resValues = true }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST}" } }
}

dependencies {
  implementation(projects.shared)
  implementation(libs.compose.activity)
  implementation(libs.androidx.core)
  implementation(libs.bundles.compose.mp)
  implementation(libs.bundles.lifecycle.mp)
  implementation(libs.kotlinx.coroutines.android)
  debugImplementation(libs.compose.ui.tooling)
}
