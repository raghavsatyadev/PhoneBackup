import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(projects.shared)
  implementation(compose.desktop.currentOs)
  implementation(libs.bundles.compose.mp)
  implementation(libs.bundles.lifecycle.mp)
  implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
  application {
    mainClass = "io.github.raghavsatyadev.phonebackup.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
      packageName = "PhoneBackup Viewer"
      packageVersion = "1.0.0"
    }
  }
}
