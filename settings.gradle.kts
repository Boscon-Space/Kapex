rootProject.name = "kapex"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}

include(":hal", ":protocol", ":drivers", ":llm", ":agent", ":analysis", ":camera", ":flightd", ":camrec", ":probes", ":station")
