pluginManagement {
    repositories {
        // 顺序即优先级：ThinkPad 实测 services.gradle.org 超时、maven.aliyun.com 534ms 可达，
        // 所以镜像放前面，官方源兜底。见 evidence 与 docs/COMPATIBILITY.md。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}
rootProject.name = "phoneagent"
include(":app")
