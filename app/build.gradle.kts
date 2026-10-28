import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from launcher/keystore.properties (never committed):
//   storeFile=/abs/path/tv-home.jks
//   storePassword=...
//   keyAlias=tvhome
//   keyPassword=...
// Personal settings (cameras, teams, weather city, football API key) live in personal.properties, which is
// git-ignored; see personal.example.properties. Without it the app builds with those features switched off.
val personalProps = Properties().apply {
    val f = rootProject.file("personal.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
fun personal(key: String): String = personalProps.getProperty(key, "").trim()

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.dhruv.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.dhruv.tvhome"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.4.0"
        ndk {
            abiFilters += setOf("armeabi-v7a")   // this TV is a 32-bit ARM board; keeps the APK small
        }
        resValue("string", "football_api_key", personal("football_api_key"))
        resValue("string", "teams_config", personal("teams"))
        resValue("string", "cameras_config", personal("cameras"))
        resValue("string", "weather_lat", personal("weather_lat"))
        resValue("string", "weather_lon", personal("weather_lon"))
        resValue("string", "weather_city", personal("weather_city"))
        resValue("string", "team_names", personal("team_names").ifEmpty { "short" })
        resValue("string", "hidden_apps", personal("hidden_apps"))
        resValue("string", "key_cameras", personal("key_cameras"))
        resValue("string", "key_home", personal("key_home"))
        resValue("string", "ambient_minutes", personal("ambient_minutes").ifEmpty { "5" })
    }

    signingConfigs {
        // Fixed debug key (not secret) so CI debug builds install over each other with `adb install -r`.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a private keystore, sign with the committed debug key so releases always install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        textReport = true
        // No network access to fetch newer dependency versions in CI / offline builds.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "UnsafeOptInUsageError")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/*.kotlin_module", "kotlin/**")
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // Live view of the cameras (RTSP). VLC takes the camera user and password as separate options, so
    // symbols such as @ and # in them are not a problem.
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
