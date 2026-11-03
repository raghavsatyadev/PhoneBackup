import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.multiplatform.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android {
    namespace = libs.versions.sharedNameSpace.get()
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) { minorApiLevel = 1 } }
    minSdk = libs.versions.minSdk.get().toInt()
    androidResources.enable = true
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    withHostTest {}
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.bundles.compose.mp)
      implementation(libs.bundles.lifecycle.mp)
      implementation(libs.coil.compose.mp)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.collections.immutable)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.compose.activity)
      implementation(libs.androidx.exifinterface)
      implementation(libs.kotlinx.coroutines.android)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

compose.resources {
  publicResClass = false
  packageOfResClass = "io.github.raghavsatyadev.phonebackup.shared.resources"
}
