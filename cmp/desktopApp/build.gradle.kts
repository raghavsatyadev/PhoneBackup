import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(projects.shared)
  implementation(compose.desktop.currentOs)
  implementation(libs.bundles.compose.mp)
  implementation(libs.bundles.lifecycle.mp)
  implementation(libs.kotlinx.coroutines.swing)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.jna.platform)
}

// The Go server and its web dashboard ride along in the app's resources (server/), so the app can
// start it. Build it first with `go build -o phonebackup.exe .` in ../server.
val serverResources = layout.buildDirectory.dir("appResources")
val copyServer by
  tasks.registering(Sync::class) {
    from(rootDir.resolve("../server")) {
      include("phonebackup.exe", "public/**")
      into("windows/server")
    }
    into(serverResources)
  }

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(copyServer) }

compose.desktop {
  application {
    mainClass = "io.github.raghavsatyadev.phonebackup.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
      packageName = "PhoneBackup"
      packageVersion = "1.0.0"
      appResourcesRootDir.set(serverResources)
      windows {
        iconFile.set(project.file("icon.ico"))
        menuGroup = "PhoneBackup"
        shortcut = true
        perUserInstall = true
        upgradeUuid = "5b0f3f7e-8a51-4c8e-9a4e-2f6c0d7b1a93"
      }
    }
  }
}
