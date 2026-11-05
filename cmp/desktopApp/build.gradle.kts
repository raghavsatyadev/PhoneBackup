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

// The Go server (the backup engine) rides along in the app's resources (server/), so the app can
// start it. `buildServer` runs `go build` first so the app never ships a stale server.
val serverDir = rootDir.resolve("../server")
val buildServer by
  tasks.registering(Exec::class) {
    workingDir(serverDir)
    commandLine("go", "build", "-o", "phonebackup.exe", ".")
    inputs.files(fileTree(serverDir) { include("**/*.go", "go.mod", "go.sum") })
    outputs.file(serverDir.resolve("phonebackup.exe"))
  }
val serverResources = layout.buildDirectory.dir("appResources")
val copyServer by
  tasks.registering(Sync::class) {
    dependsOn(buildServer)
    from(rootDir.resolve("../server")) {
      include("phonebackup.exe")
      into("windows/server")
    }
    into(serverResources)
  }

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(copyServer) }
// The packaging tasks do not track app resources, so a new server alone would not repackage.
tasks.matching { it.name.startsWith("createDistributable") || it.name.startsWith("createReleaseDistributable") || it.name.startsWith("package") }.configureEach { inputs.files(copyServer) }

compose.desktop {
  application {
    mainClass = "io.github.raghavsatyadev.phonebackup.desktop.MainKt"
    // `packageReleaseMsi`: ProGuard shrinks only; obfuscation and optimization break reflection.
    buildTypes.release.proguard {
      version.set("7.10.0")
      configurationFiles.from(project.file("proguard-rules.pro"))
      obfuscate.set(false)
      optimize.set(false)
    }
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

/**
 * ProGuard cannot read the Kotlin 2.5 stdlib's metadata yet. It only reads metadata when
 * `kotlin.Metadata` is kept, and Compose's default rules keep all of `kotlin.**`, so the release
 * build uses a copy of those rules without it. Same fix as JobFinder and SCUS.
 */
val composeRulesWithoutMetadata by tasks.registering {
  val unpacked =
    tasks.named("unpackDefaultComposeDesktopJvmApplicationResources").map {
      it.outputs.files.asFileTree.matching { include("**/default-compose-desktop-rules.pro") }
    }
  val out = layout.buildDirectory.file("compose/proguard/compose-rules.pro")
  inputs.files(unpacked)
  outputs.file(out)
  doLast {
    val original = unpacked.get().singleFile
    val text = original.readText()
    val keepKotlin = "-keep class kotlin.** { *; }"
    check(keepKotlin in text) { "Compose's ProGuard rules changed: recheck $original" }
    out.get().asFile.writeText(text.replace(keepKotlin, "-keep class !kotlin.Metadata,kotlin.** { *; }"))
  }
}

// After the Compose plugin, which sets its own rules file in afterEvaluate.
afterEvaluate {
  tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask>().configureEach {
    defaultComposeRulesFile.set(composeRulesWithoutMetadata.map { RegularFile { it.outputs.files.singleFile } })
  }
}
