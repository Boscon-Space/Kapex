rootProject.name = "hab1-payload"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(":hal", ":protocol", ":drivers", ":llm", ":agent", ":analysis", ":flightd", ":probes")
