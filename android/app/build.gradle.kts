import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

plugins {
    id("com.android.application")
}

/** Repository root: the Cargo workspace and the rootfs from `scripts/fetch-rootfs` are here. */
val repoRoot: Directory = rootProject.layout.projectDirectory.dir("..")

/** Both Zig builds use the local toolchain override before the environment and PATH. */
val zigExecutable = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orElse("")
    .map { text ->
        Properties().apply { load(text.reader()) }
            .getProperty("zig.path")?.trim()?.takeIf { it.isNotEmpty() }
    }
    .orElse(providers.environmentVariable("ZIG"))
    .orElse("zig")

/** The fetcher's pinned inventory is shared with release-sources. Listing it works offline. */
val rootfsAssets = providers.exec {
    commandLine(repoRoot.file("scripts/fetch-rootfs").asFile, "--assets")
}.standardOutput.asText.map { it.trim().lines() }

val alpineBranch = rootfsAssets.map { inventory ->
    val tarball = inventory.last().substringBefore(' ')
    val version = tarball.removePrefix("alpine-minirootfs-").substringBefore("-aarch64.")
    "v${version.substringBeforeLast('.')}"
}

android {
    namespace = "io.github.mthli.appine"

    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.mthli.appine"
        minSdk = 30
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ALPINE_BRANCH", "\"${alpineBranch.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Separate package ID so debug and release can be installed side by side
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Installed locally only: signed with the debug key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Native libraries are stored uncompressed and page-aligned in the APK and mapped directly
        // from it (also on 16K-page devices)
        jniLibs.useLegacyPackaging = false
    }

    lint {
        // A lint warning fails lint (CI runs lintDebug), as `-D warnings` fails clippy.
        warningsAsErrors = true
        // These follow what is published or dated outside the repository (newer libraries, AGP or
        // SDK levels, Play's target SDK deadlines, the Play SDK Index), so as errors they would fail
        // an unchanged tree; they stay in the report.
        informational += listOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
            "ExpiringTargetSdkVersion",
            "OutdatedLibrary",
        )
    }
}

kotlin {
    compilerOptions {
        // A compiler warning fails the build, main and test sources alike.
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.material)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.junit)
}

val rootfsAsset = tasks.register<RootfsAssetTask>("rootfsAsset") {
    inventory.set(rootfsAssets)
    val cache = repoRoot.dir("target/rootfs")
    sources.from(rootfsAssets.map { rows -> rows.map { cache.file(it.substringBefore(' ')) } })
    outputDir.set(layout.buildDirectory.dir("generated/rootfs/assets"))
}

val browserAsset = tasks.register<BrowserAssetTask>("browserAsset") {
    source.set(repoRoot.file("hosts/android/open-url.c"))
    zig.set(zigExecutable)
    outputDir.set(layout.buildDirectory.dir("generated/browser/assets"))
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar(Char::uppercase)
        val buildRust = tasks.register<CargoNdkTask>("buildRust$suffix") {
            workspace.set(repoRoot)
            zig.set(zigExecutable)
            ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.path })
            when (variant.buildType) {
                "debug" -> cargoArgs.addAll("--profile", "release", "--features", "test-hooks,borrow-check")
                "release" -> cargoArgs.addAll("--profile", "android-release")
                else -> cargoArgs.addAll("--profile", "release")
            }
            outputDir.set(layout.buildDirectory.dir("generated/rust/${variant.name}/jniLibs"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRust, CargoNdkTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(rootfsAsset, RootfsAssetTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(browserAsset, BrowserAssetTask::outputDir)
    }
}

/** A static Linux launcher: no guest curl, netcat, shell extensions or libc installation needed. */
abstract class BrowserAssetTask : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:Input
    abstract val zig: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    init {
        // Zig's compilation cache also tracks the compiler and its bundled musl sources.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile.apply { mkdirs() }
        exec.exec {
            commandLine(zig.get(), "cc", "-target", "aarch64-linux-musl", "-static", "-O2", "-s",
                "-std=c11", "-Wall", "-Wextra", "-Werror", source.get().asFile.path,
                "-o", File(out, "appine-open-url").path)
        }
    }
}

/**
 * Builds libappine.so with cargo-ndk. Both APKs need an optimized interpreter.
 * The debug APK enables kernel test hooks and borrow checks for instrumentation.
 */
abstract class CargoNdkTask : DefaultTask() {
    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Input
    abstract val zig: Property<String>

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:Input
    abstract val cargoArgs: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    init {
        // cargo does its own incremental builds
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val root = workspace.get().asFile
        exec.exec {
            workingDir = root
            executable = cargo()
            args(
                "ndk", "-t", "arm64-v8a", "--platform", "30",
                "-o", outputDir.get().asFile.path,
                "build", "-p", "appine-android",
            )
            args(cargoArgs.get())
            // Set both: with only one set, when the other one in the environment points at another
            // NDK (GitHub's runners preset ANDROID_NDK_ROOT), cargo-ndk warns, and which one it
            // uses depends on its order of precedence.
            environment("ANDROID_NDK_HOME", ndkDirectory.get())
            environment("ANDROID_NDK_ROOT", ndkDirectory.get())
            environment("ZIG", zig.get())
            environment("CARGO_TARGET_DIR", File(root, "target/android").path)
        }
    }

    /** The cargo under `~/.cargo/bin` when there is one, else the one in `PATH`. */
    private fun cargo(): String = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        .takeIf { it.canExecute() }?.path ?: "cargo"
}

/**
 * Puts the rootfs tarball into the assets (imported on first start, the android/setup map), the
 * doas package (for the login account) as `doas.apk`, and bash's packages (the login shell of the
 * systems the app imports) under `bash/`, procps-ng with its libraries under `procps/`, and
 * bubblewrap with libcap under `bubblewrap/`, and the first-boot packages under `openrc/`.
 */
abstract class RootfsAssetTask : DefaultTask() {
    @get:Input
    abstract val inventory: ListProperty<String>

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun verifiedBytes(source: File, expectedSha256: String): ByteArray {
        if (!source.isFile) throw GradleException("Missing ${source.name}. Run scripts/fetch-rootfs first.")
        val bytes = source.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (HexFormat.of().formatHex(digest) != expectedSha256) {
            throw GradleException("Checksum mismatch for ${source.name}. Run scripts/fetch-rootfs.")
        }
        return bytes
    }

    @TaskAction
    fun copy() {
        val files = sources.files.associateBy { it.name }
        val assets = mutableMapOf<String, ByteArray>()
        for (entry in inventory.get()) {
            val fields = entry.split(' ')
            val (name, sha256) = fields
            val bytes = verifiedBytes(files.getValue(name), sha256)
            for (dest in fields.drop(2)) {
                val path = if (dest.endsWith('/')) dest + name else dest
                assets[path] = bytes
            }
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for ((path, bytes) in assets) {
            val target = out.resolve(path)
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
    }
}
