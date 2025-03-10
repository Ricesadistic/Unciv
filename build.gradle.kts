import com.unciv.build.BuildConfig.gdxVersion
import com.unciv.build.BuildConfig.roboVMVersion


// You'll still get kotlin-reflect-1.3.70.jar in your classpath, but will no longer be used
configurations.all { resolutionStrategy {
    force("org.jetbrains.kotlin:kotlin-reflect:${com.unciv.build.BuildConfig.kotlinVersion}")
} }


buildscript {

    repositories {
        // Chinese mirrors for quicker loading for chinese devs - uncomment if you're chinese
        // maven{ url = uri("https://maven.aliyun.com/repository/jcenter") }
        // maven{ url = uri("https://maven.aliyun.com/repository/google") }
        // maven{ url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // maven{ url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
        gradlePluginPortal()
        maven{ url = uri("https://jitpack.io") } // for the anuken packr
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${com.unciv.build.BuildConfig.kotlinVersion}")
        classpath("de.richsource.gradle.plugins:gwt-gradle-plugin:0.6")
        classpath("com.android.tools.build:gradle:7.0.2")
        classpath("com.mobidevelop.robovm:robovm-gradle-plugin:2.3.1")

        // This is for wrapping the .jar file into a standalone executable
        classpath("com.github.anuken:packr:-SNAPSHOT")
    }
}
        
allprojects {
    apply(plugin  = "eclipse")
    apply(plugin  = "idea")


    version = "1.0.1"

    repositories {
        // Chinese mirrors for quicker loading for chinese devs - uncomment if you're chinese
        // maven{ url = uri("https://maven.aliyun.com/repository/jcenter") }
        // maven{ url = uri("https://maven.aliyun.com/repository/google") }
        // maven{ url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // maven{ url = uri("https://maven.aliyun.com/repository/public") }
        google()
        gradlePluginPortal()
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/releases/") }
        maven{ url = uri("https://jitpack.io") } // for java-discord-rpc
    }
}

project(":desktop") {
    apply(plugin = "kotlin")

    dependencies {
        "implementation"(project(":core"))
        "implementation"("com.badlogicgames.gdx:gdx-backend-lwjgl3:${gdxVersion}")
        "implementation"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

        "implementation"("com.badlogicgames.gdx:gdx-tools:$gdxVersion") {
            exclude("com.badlogicgames.gdx", "gdx-backend-lwjgl")
        }

        "implementation"("com.github.MinnDevelopment:java-discord-rpc:v2.0.1")
    }

}

project(":android") {
    apply(plugin = "com.android.application")
    apply(plugin = "kotlin-android")

    val natives by configurations.creating

    dependencies {
        "implementation"(project(":core"))
        "implementation"("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
        natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")
    }
}

project(":ios") {
    apply(plugin = "kotlin")
    apply(plugin = "robovm")

    dependencies {
        "implementation"(project(":core"))
        "implementation"("com.mobidevelop.robovm:robovm-rt:$roboVMVersion")
        "implementation"("com.mobidevelop.robovm:robovm-cocoatouch:$roboVMVersion")
        "implementation"("com.badlogicgames.gdx:gdx-backend-robovm:$gdxVersion")
        "implementation"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-ios")
    }
}


project(":core") {
    apply(plugin = "kotlin")

    dependencies {
        "implementation"("com.badlogicgames.gdx:gdx:$gdxVersion")
    }


    // Taken from https://github.com/TomGrill/gdx-testing
    project(":tests") {
        apply(plugin = "java")
        apply(plugin = "kotlin")

        dependencies {
            "implementation"(project(":core"))

            "implementation"("junit:junit:4.13.1")
            "implementation"("org.mockito:mockito-all:1.10.19")

            "implementation"("com.badlogicgames.gdx:gdx-backend-headless:$gdxVersion")
            "implementation"("com.badlogicgames.gdx:gdx:$gdxVersion")

            "testImplementation"("junit:junit:4.13.1")
            "testImplementation"("org.mockito:mockito-all:1.10.19")
            "testImplementation"("io.mockk:mockk:1.9.3")

            "testImplementation"("com.badlogicgames.gdx:gdx-backend-headless:$gdxVersion")
            "testImplementation"("com.badlogicgames.gdx:gdx:$gdxVersion")
            "testImplementation"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
        }
    }
}
