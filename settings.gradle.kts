rootProject.name = "hab1-payload"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}

include(":hal", ":protocol", ":drivers", ":llm", ":agent", ":analysis", ":flightd", ":probes", ":station")
