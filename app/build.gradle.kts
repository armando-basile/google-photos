import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(name: String, default: String = ""): String =
    localProps.getProperty(name) ?: System.getenv(name) ?: default

android {
    namespace = "dev.armando.tvphotos"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.armando.tvphotos"
        minSdk = 25
        targetSdk = 34
        // La CI passa i valori ricavati dal tag (vedi .github/workflows/release.yml).
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersionName") as String?) ?: "0.1.0"

        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${prop("GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${prop("GOOGLE_CLIENT_SECRET")}\"")
        buildConfigField(
            "String",
            "PAIRING_PAGE_URL",
            "\"${prop("PAIRING_PAGE_URL", "https://armando-basile.github.io/google-photos/")}\"",
        )
    }

    // Keystore in keys/ (non versionata); le password arrivano da local.properties o dall'ambiente (CI).
    val releaseKeystore = rootProject.file(prop("KEYSTORE_FILE", "keys/appgroups-release.jks"))
    val releaseStorePassword = prop("KEYSTORE_PASSWORD")
    signingConfigs {
        if (releaseKeystore.exists() && releaseStorePassword.isNotBlank()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = prop("KEY_ALIAS", "appgroups")
                keyPassword = prop("KEY_PASSWORD").ifBlank { releaseStorePassword }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Senza password del keystore si usa la chiave di debug (solo build locali).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
}
