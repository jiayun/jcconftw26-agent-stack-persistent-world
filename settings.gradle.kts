pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "persistent-story-world"
include("domain", "application", "ai-adapters", "server")
