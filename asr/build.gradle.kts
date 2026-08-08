import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.asr"
    compileSdk = 36

    // 从根目录 secrets.properties 读取 Evolia ASR API Key（该文件已在 .gitignore 中）
    val secretsProperties = Properties()
    val secretsFile = rootProject.file("secrets.properties")
    if (secretsFile.exists()) {
        secretsProperties.load(FileInputStream(secretsFile))
    }
    val evoliaAsrApiKey = secretsProperties.getProperty("EVOLIA_ASR_API_KEY") ?: ""

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Evolia ASR API Key 注入 BuildConfig（源码中不出现明文 key）
        buildConfigField("String", "EVOLIA_ASR_API_KEY", "\"$evoliaAsrApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    // sherpa-onnx: Silero VAD（用于在线 ASR 的语音段切分）
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.4")
}
