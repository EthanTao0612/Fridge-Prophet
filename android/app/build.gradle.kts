import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 签名配置从 keystore.properties 读，文件不存在就用 debug 签名，保证新人 clone 下来能直接编译
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// ---------- 后端地址注入 ----------
// 优先级：-PAPI_BASE_URL=...  >  环境变量 API_BASE_URL  >  android/gradle.properties  >  下面的内置默认值
//
// 为什么做成可注入而不是直接写死：
//   1. 换服务器地址是部署阶段的日常操作，不该每次都去改这个文件；
//   2. 写死在代码里，一旦提交进 Git，别人 clone 下来就指向了你的服务器；
//   3. GitHub Actions 无法在不改代码的前提下打出指向正式服务器的包，只能靠注入。
// 注意 -P 和 gradle.properties 走的是同一条路（都由 findProperty 读取），环境变量单独兜底。
val injectedBaseUrl: String? =
    (project.findProperty("API_BASE_URL") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("API_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }

/** 统一补上结尾斜杠 —— Retrofit 的 baseUrl 必须以 "/" 结尾，否则运行时直接抛异常。 */
fun apiBaseUrl(fallback: String): String {
    val raw = injectedBaseUrl ?: fallback
    return if (raw.endsWith("/")) raw else "$raw/"
}

android {
    namespace = "com.fridgeprophet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fridgeprophet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                // 注意：这里必须用 rootProject.file(...) 而不是 file(...)。
                // 在 android { } 块里 file() 是相对 app/ 解析的，而 keystore.properties
                // 位于 android/ 根目录下，用 file() 会去找 android/app/release.jks 然后报找不到。
                // 用 rootProject 解析后，配置里写 release.jks 就等于 android/release.jks；
                // 写绝对路径（如 G:\keys\release.jks）同样支持。
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 默认 10.0.2.2 是 Android 模拟器指向宿主机的固定地址。
            // 真机调试要换成电脑的局域网 IP，例如：
            //   ./gradlew installDebug -PAPI_BASE_URL=http://192.168.1.23:8000/
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("http://10.0.2.2:8000/")}\"")
            isMinifyEnabled = false
            // 加后缀后，debug 包和 release 包可以同时装在一台手机上，互不覆盖
            applicationIdSuffix = ".debug"
        }
        release {
            // 占位域名。打正式包时必须注入真实地址，否则构建日志会给出警告。
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("https://api.example.com/")}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // CameraX：拍冰箱照片
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // 图片加载：菜谱配图 / 用户头像。
    // 这里**故意不引第三方图片库**（试过 Coil 3：它的 KMP 元数据会带一个
    // platform-runtime 约束，和本项目 Gradle 8.14.5 的依赖图序列化不兼容，
    // 直接让 processDebugResources 失败）。改成用上面已有的 OkHttp + BitmapFactory
    // 自己实现，见 ui/components/RemoteImage.kt —— 零新增依赖，构建链不再变脆。

    // ---------- 单元测试（只在 JVM 上跑，不进 APK）----------
    // 加测试的唯一目的：把「DELETE 接口必须能接受 204 空响应体」这条约束钉死。
    // 这个 bug 已经复发两次（表现是「所有删除功能全部失效」），
    // 光靠注释挡不住，必须有能跑的红绿灯。
    testImplementation("junit:junit:4.13.2")
    // 版本必须和 okhttp 一致，见 libs.versions.toml 的 okhttp
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
}

// ---------- 打正式包时的防空提醒 ----------
// release 包如果没注入地址，会静默使用占位域名 api.example.com，
// 装到手机上表现为「一打开就转圈 / 网络错误」，排查起来很费时间。
// 所以在构建日志里主动喊一声，把问题提前到打包那一刻暴露。
if (injectedBaseUrl == null) {
    val buildingRelease = gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = true) ||
            it.startsWith("bundle", ignoreCase = true) ||
            it.startsWith("install", ignoreCase = true)
    }
    if (buildingRelease) {
        logger.warn(
            """
            |
            |[WARN] 本次构建没有注入 API_BASE_URL，包内将使用占位地址。
            |       debug  默认 http://10.0.2.2:8000/      —— 只对模拟器有效
            |       release 默认 https://api.example.com/  —— 这个域名不存在
            |
            |       打正式包请显式指定：
            |         ./gradlew assembleRelease -PAPI_BASE_URL=https://你的域名/
            |
            |       （前缀用 [WARN] 而不是中文，是为了在 Windows 控制台里能直接 grep 到，
            |         中文在 GBK 终端下会让 grep 把输出当成二进制文件而匹配失败）
            |""".trimMargin()
        )
    }
}
