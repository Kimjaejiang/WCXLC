/**
 * 仓库策略说明（2026-08-27 修复）：
 * - 阿里云镜像置前：国内构建优先命中镜像（官方源在国内慢/偶发 301/302），
 *   镜像缺失的 artifact（404）会自动回落到后面的官方源。
 * - miuix 0.9.3 在 Maven Central 有官方发布，移除 GitHub Packages
 *   (maven.pkg.github.com) 源——该源匿名请求 403，只会在日志里制造 301/302/403 噪音。
 * - de.robv.android.xposed:api:82 不在 Maven Central（repo1 404），
 *   api.xposed.info 是唯一来源，必须保留。
 */
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.Ujhhgtg")
                includeGroup("com.github.Ujhhgtg.rhino")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        mavenCentral()
    }

    versionCatalogs {
        create("libs")
    }
}

rootProject.name = "WX2026"

include(
    ":app",
    ":libs:common:annotation-scanner",
    ":libs:common:stubs",
    ":libs:common:bsh",
    ":libs:common:reflekt"
)
