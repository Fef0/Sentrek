import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.sentrek"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.sentrek"
        minSdk = 24
        targetSdk = 37
        versionCode = 22
        versionName = "0.17.0"

        // The languages the app is translated into, from its res/values-<language> folders: a
        // new translation shows in the language list by itself (see TRANSLATING.md).
        buildConfigField("String[]", "LANGUAGES", languages().joinToString(prefix = "{", postfix = "}") { "\"$it\"" })
    }

    // The same list for the phone's own per-app language setting (Android 13+).
    androidResources {
        generateLocaleConfig = true
    }

    // The release key lives outside the repo; keystore.properties (git-ignored) points to it.
    // Without it, release builds come out unsigned.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // MapLibre's native code is most of the APK: one APK per CPU type, plus a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // F-Droid rejects the encrypted dependency list Google adds to the signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:geo"))
    implementation(project(":core:map"))
    implementation(project(":core:ui"))
    implementation(project(":feature:maps"))
    implementation(project(":feature:record"))
    implementation(project(":feature:planner"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

/** "en" (res/values), then each translation's tag, from values-it, values-pt-rBR, values-b+sr+Latn. */
fun languages(): List<String> {
    val res = file("src/main/res")
    val simple = Regex("""values-([a-z]{2,3})(?:-r([A-Z]{2}))?""")
    val bcp = Regex("""values-b\+([A-Za-z0-9+]+)""")
    val tags = res.listFiles().orEmpty()
        .filter { File(it, "strings.xml").exists() }
        .mapNotNull { dir ->
            simple.matchEntire(dir.name)?.let { m -> listOfNotNull(m.groupValues[1], m.groupValues[2].ifEmpty { null }).joinToString("-") }
                ?: bcp.matchEntire(dir.name)?.groupValues?.get(1)?.replace('+', '-')
        }
        .sorted()
    return listOf("en") + tags
}
