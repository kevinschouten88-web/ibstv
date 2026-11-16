plugins {
    id("com.android.application")
}

// Ondertekenen met de uploadsleutel; de gegevens komen uit GitHub-geheimen (nooit in de code).
val keystorePath: String? = System.getenv("UPLOAD_KEYSTORE")
val hasKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "nl.iptvspeler.tv"          // Java-pakket van de broncode
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.ibstv.app"      // de naam in Google Play; kan na publicatie nooit meer veranderen
        minSdk = 23
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "10").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.3.0"
    }

    signingConfigs {
        // Testsleutel: alleen zolang de uploadsleutel nog niet in GitHub staat, zodat je de APK kunt installeren.
        create("test") {
            storeFile = rootProject.file("test-key.jks")
            storePassword = "ibstv-test"
            keyAlias = "test"
            keyPassword = "ibstv-test"
        }
        if (hasKey) create("upload") {
            storeFile = file(keystorePath!!)
            storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("UPLOAD_KEY_PASSWORD") ?: System.getenv("UPLOAD_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasKey) "upload" else "test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources { excludes += "META-INF/*.kotlin_module" }
    }

    androidResources {
        // De speler (www) staat zo ongecomprimeerd in de app; dat laadt sneller.
        noCompress += listOf("woff2", "png")
    }
}

dependencies {
    // Chromecast
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
}
