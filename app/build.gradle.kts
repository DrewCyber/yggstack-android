plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("kotlin-parcelize")
}

import java.util.Properties
import java.io.FileInputStream
import java.io.File

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
        val process = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotEmpty()) output else null
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
    compileSdk = 34

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
        vectorDrawables {
            useSupportLibrary = true
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
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

dependencies {
    // Yggstack library
    implementation(files("libs/yggstack.aar"))

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Data & Storage
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // DNS lookup for IP detection fallback
    implementation("dnsjava:dnsjava:3.5.3")

    // MIUI Autostart permission check
    implementation("com.github.XomaDev:MIUI-autostart:v1.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

