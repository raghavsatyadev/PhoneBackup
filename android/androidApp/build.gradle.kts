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
