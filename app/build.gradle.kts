import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties
import kotlin.math.sign

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // 移除 Firebase Crashlytics 插件
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 36
    val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }

    // 安全读取 local.properties
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }
    val buglyAppId = localProperties.getProperty("bugly.appid") ?: ""
    val buglyAppKey = localProperties.getProperty("bugly.appkey") ?: ""

    defaultConfig {
        applicationId = "ailand.lastchat.rikkafork.cocolal"
        minSdk = 28
        targetSdk = 36
        versionCode = 27
        versionName = "4.5.1.0"

        buildConfigField("String", "GITHUB_REPO", "\"dragonstaryuri-sys/evolia\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }

        manifestPlaceholders["extractNativeLibs"] = "true"
        // 默认启用
        resValue("bool", "text_selection_enabled", "true")

        // 将读取到的 Bugly ID/Key 注入 BuildConfig
        buildConfigField("String", "BUGLY_APP_ID", "\"$buglyAppId\"")
        buildConfigField("String", "BUGLY_APP_KEY", "\"$buglyAppKey\"")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/DEPENDENCIES"

            merges += "META-INF/mailcap"
            merges += "META-INF/javamail.providers"
            merges += "META-INF/javamail.default.providers"
            merges += "META-INF/javamail.default.address.map"
            merges += "META-INF/javamail.address.map"
        }
    }

    splits {
        abi {
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true

            val storeFilePath = localProperties.getProperty("storeFile")
            val storePasswordValue = localProperties.getProperty("storePassword")
            val keyAliasValue = localProperties.getProperty("keyAlias")
            val keyPasswordValue = localProperties.getProperty("keyPassword")

            if (storeFilePath != null && storePasswordValue != null &&
                keyAliasValue != null && keyPasswordValue != null
            ) {
                val keystoreFile = rootProject.file(storeFilePath)
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    // 自动修复无效的外部签名注入
    signingConfigs.all {
        val config = this
        if (config.storeFile != null && !config.storeFile!!.exists()) {
            println("警告: 签名配置 '${config.name}' 指向的路径不存在: ${config.storeFile}。已自动跳过该配置。")
            config.storeFile = null
        }
    }

    buildTypes {
        release {
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile != null && releaseSigningConfig.storeFile?.exists() == true) {
                signingConfig = releaseSigningConfig
            } else {
                signingConfig = null
                println("提示: 当前未配置有效的 Release 签名。")
            }

            // 开源项目不混淆：避免 R8 把 Sherpa-Onnx JNI 字段名混淆后导致 ASR/VAD 崩溃
            isMinifyEnabled = false
            isShrinkResources = false
            isZipAlignEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("int", "VERSION_CODE", "${android.defaultConfig.versionCode}")
            resValue("bool", "text_selection_enabled", "false")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isZipAlignEnabled = true
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("int", "VERSION_CODE", "${android.defaultConfig.versionCode}")
            resValue("bool", "text_selection_enabled", "true")
        }
        create("baseline") {
            initWith(getByName("release"))
            matchingFallbacks.add("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true

            resValue("bool", "text_selection_enabled", "false")
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
    androidResources {
        generateLocaleConfig = true
    }
    applicationVariants.all {
        outputs.all {
            this as com.android.build.gradle.internal.api.ApkVariantOutputImpl

            val variantName = name
            val apkName = "evolia_" + defaultConfig.versionName + "_" + variantName + ".apk"

            outputFileName = apkName
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        val localProperties = Properties().apply {
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                FileInputStream(localPropsFile).use { load(it) }
            }
        }

        val configuredBuildPython = providers.gradleProperty("chaquopy.buildPython").orNull
            ?: System.getenv("CHAQUOPY_BUILD_PYTHON")
            ?: System.getenv("PYTHON")
            ?: System.getenv("PYTHON3")
            ?: localProperties.getProperty("chaquopy.buildPython")
        if (!configuredBuildPython.isNullOrBlank()) {
            buildPython(configuredBuildPython)
        }

        pip {
            install("numpy")
            install("pandas")
            install("matplotlib")
            install("Pillow")
            install("openpyxl")
            install("python-pptx")
            install("pypdf")
            install("python-docx")
            install("requests")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    implementation(libs.androidx.navigation2)

    // Bugly
    implementation(libs.bugly.crashreport)
    implementation(libs.bugly.nativecrashreport)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security - EncryptedSharedPreferences for API keys
    implementation(libs.androidx.security.crypto)

    // Image metadata extractor
    implementation(libs.metadata.extractor)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // pebble (template engine)
    implementation(libs.pebble)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Media3 (ExoPlayer) - 语音消息播放
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Palette (for color extraction from images)
    implementation(libs.androidx.palette.ktx)

    // WebDav
    implementation(libs.dav4jvm) {
        exclude(group = "org.ogce", module = "xpp3")
    }

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // modules
    implementation(project(":ai"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":tts"))
    implementation(project(":asr"))
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.4")
    implementation(project(":common"))
    implementation(project(":core-data"))
    implementation(project(":discover"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Glance (Widgets)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.material3)

    // JavaMail for Android
    implementation(libs.android.mail)
    // lottie anime
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")

    // Excel 处理库
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // OpenCV for Android (手写日记四角透视校正)
    implementation("org.opencv:opencv:4.11.0")

    // tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
