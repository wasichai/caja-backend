rootProject.name = "caja-backend"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // wasichai's libraries. GitHub Packages wants a token even to read: read:packages is enough
        maven {
            name = "wasichai"
            url = uri("https://maven.pkg.github.com/wasichai/wasichai")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("wasichai") }
        }
        // fallback until a wasichai release is published: ./gradlew publishToMavenLocal in a wasichai checkout
        mavenLocal {
            content { includeGroup("wasichai") }
        }
    }
}
