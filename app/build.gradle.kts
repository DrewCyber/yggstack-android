plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

import java.util.Properties
import java.io.FileInputStream
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

data class RepoVersionInfo(
    val tag: String?,
    val commit: String?,
    val repoUrl: String
) {
    val displayValue: String
        get() = when {
            !tag.isNullOrBlank() && !commit.isNullOrBlank() -> "$tag - $commit"
            !commit.isNullOrBlank() -> commit
            !tag.isNullOrBlank() -> tag
            else -> "unknown"
        }

    val versionNameValue: String
        get() = when {
            !tag.isNullOrBlank() && !commit.isNullOrBlank() -> "$tag-$commit"
            !commit.isNullOrBlank() -> commit
            !tag.isNullOrBlank() -> tag
            else -> "0.0.1"
        }

    val commitUrl: String
        get() = if (!commit.isNullOrBlank()) "$repoUrl/commit/$commit" else repoUrl
}

fun runCommand(workingDir: File, vararg command: String): String? {
    return try {
        // providers.exec instead of ProcessBuilder: keeps these git/go
        // lookups compatible with the Gradle configuration cache
        val output = providers.exec {
            workingDir(workingDir)
            commandLine(*command)
        }.standardOutput.asText.get().trim()
        if (output.isNotEmpty()) output else null
    } catch (e: Exception) {
        null
    }
}

fun normalizeGitHubUrl(remoteUrl: String?): String? {
    if (remoteUrl.isNullOrBlank()) return null
    return when {
        remoteUrl.startsWith("git@github.com:") -> {
            "https://github.com/" + remoteUrl.removePrefix("git@github.com:").removeSuffix(".git")
        }
        remoteUrl.startsWith("https://github.com/") -> remoteUrl.removeSuffix(".git")
        else -> null
    }
}

fun resolveGitRepoVersion(repoDir: File, fallbackRepoUrl: String, preferredTag: String? = null): RepoVersionInfo {
    val commit = runCommand(repoDir, "git", "rev-parse", "--short", "HEAD")
    val tag = preferredTag
        ?: runCommand(repoDir, "git", "describe", "--tags", "--abbrev=0")?.removePrefix("v")
    val repoUrl = normalizeGitHubUrl(runCommand(repoDir, "git", "remote", "get-url", "origin"))
        ?: fallbackRepoUrl

    return RepoVersionInfo(tag = tag, commit = commit, repoUrl = repoUrl)
}

fun resolveGoModuleVersion(moduleDir: File, moduleName: String, fallbackRepoUrl: String): RepoVersionInfo {
    val moduleVersion = Regex("$moduleName\\s+v([^\\s]+)")
        .find(moduleDir.resolve("go.mod").readText())
        ?.groupValues
        ?.get(1)

    val metadata = runCommand(moduleDir, "go", "mod", "download", "-json", "$moduleName@v$moduleVersion")
    val commit = metadata
        ?.let { Regex("\"Hash\"\\s*:\\s*\"([0-9a-f]{40})\"").find(it)?.groupValues?.get(1) }
        ?.take(7)

    return RepoVersionInfo(
        tag = moduleVersion,
        commit = commit,
        repoUrl = fallbackRepoUrl
    )
}

val appVersionInfo by lazy {
    resolveGitRepoVersion(
        repoDir = rootProject.projectDir,
        fallbackRepoUrl = "https://github.com/DrewCyber/yggstack-android"
    )
}

val yggstackVersionInfo by lazy {
    resolveGitRepoVersion(
        repoDir = rootProject.file("lib/yggstack"),
        fallbackRepoUrl = "https://github.com/DrewCyber/yggstack"
    )
}

val yggdrasilVersionInfo by lazy {
    resolveGoModuleVersion(
        moduleDir = rootProject.file("lib/yggstack"),
        moduleName = "github.com/yggdrasil-network/yggdrasil-go",
        fallbackRepoUrl = "https://github.com/yggdrasil-network/yggdrasil-go"
    )
}

// Get version from git tag or environment variable
fun getVersionName(): String {
    // Try to get from environment variable (GitHub Actions)
    val envVersion = System.getenv("APP_VERSION")
    if (!envVersion.isNullOrEmpty()) {
        return envVersion
    }

    return appVersionInfo.versionNameValue
}

// Calculate versionCode from semantic version (e.g., 1.2.3 -> 10203)
fun getVersionCode(): Int {
    return try {
        val versionName = getVersionName()
        // Extract version without commit hash (e.g., "1.2.3-abc123" -> "1.2.3")
        val version = versionName.split("-")[0]
        val parts = version.split(".")
        
        if (parts.size >= 3) {
            val major = parts[0].toIntOrNull() ?: 0
            val minor = parts[1].toIntOrNull() ?: 0
            val patch = parts[2].toIntOrNull() ?: 0
            
            // Calculate: major * 10000 + minor * 100 + patch
            // Max values: 99.99.99 = 999999
            major * 10000 + minor * 100 + patch
        } else {
            1
        }
    } catch (e: Exception) {
        1
    }
}

fun getCommitHash(): String {
    return appVersionInfo.commit ?: "unknown"
}

// Load keystore properties for local builds
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "link.yggdrasil.yggstack.android"
    compileSdk = 36
    // Must match the NDK installed by CI (.github/workflows/build-release.yml)
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "link.yggdrasil.yggstack.android"
        minSdk = 23
        targetSdk = 34
        versionCode = getVersionCode()
        versionName = getVersionName()

        // Generate BuildConfig fields
        buildConfigField("String", "VERSION_NAME", "\"${getVersionName()}\"")
        buildConfigField("String", "COMMIT_HASH", "\"${getCommitHash()}\"")
        buildConfigField("String", "APP_VERSION_DISPLAY", "\"${appVersionInfo.displayValue}\"")
        buildConfigField("String", "APP_VERSION_URL", "\"${appVersionInfo.commitUrl}\"")
        buildConfigField("String", "YGGSTACK_VERSION_DISPLAY", "\"${yggstackVersionInfo.displayValue}\"")
        buildConfigField("String", "YGGSTACK_VERSION_URL", "\"${yggstackVersionInfo.commitUrl}\"")
        buildConfigField("String", "YGGDRASIL_VERSION_DISPLAY", "\"${yggdrasilVersionInfo.displayValue}\"")
        buildConfigField("String", "YGGDRASIL_VERSION_URL", "\"${yggdrasilVersionInfo.commitUrl}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing configuration
    signingConfigs {
        create("release") {
            // For local builds: use release.keystore in project root with keystore.properties
            // For GitHub Actions: keystore is decoded from secrets with env variables
            val keystorePath = System.getenv("KEYSTORE_FILE") ?: "../release.keystore"
            val keystoreFile = if (System.getenv("KEYSTORE_FILE") != null) {
                file(keystorePath)
            } else {
                val localKeystore = file("../release.keystore")
                if (localKeystore.exists()) localKeystore else null
            }
            
            if (keystoreFile != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") 
                    ?: keystoreProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") 
                    ?: keystoreProperties.getProperty("KEY_ALIAS") 
                    ?: "release"
                keyPassword = System.getenv("KEY_PASSWORD") 
                    ?: keystoreProperties.getProperty("KEY_PASSWORD") 
                    ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Use signing config if available
            val releaseSigningConfig = signingConfigs.getByName("release")
            if (releaseSigningConfig.storeFile?.exists() == true) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Disable dependencies info reporting for Google Play policy
    // (MIUI autostart library uses hidden APIs)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Yggstack library
    implementation(files("libs/yggstack.aar"))

    // Core Android
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Compose (BOMs since 2025.09 no longer manage the deprecated icons
    // artifacts, so material-icons-extended carries an explicit pin)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.navigation.compose)

    // Data & Storage
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.serialization.json)

    // DNS lookup for IP detection fallback
    implementation(libs.dnsjava)

    // MIUI Autostart permission check
    implementation(libs.miui.autostart)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

