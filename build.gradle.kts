import com.github.jk1.license.render.*

plugins {
    id("com.github.jk1.dependency-license-report") version "3.1.4"
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.asset.pack) apply false
    alias(libs.plugins.android.test) apply false
}

licenseReport {
    // Audit the dependencies actually shipped by the Android application.
    // The root project and asset-pack module do not have the same runtime
    // configuration as the Android app, so point explicitly at its release graph.
    projects = arrayOf(project(":app"))
    configurations = arrayOf("releaseRuntimeClasspath")
    renderers = arrayOf<ReportRenderer>(
        InventoryHtmlReportRenderer("index.html", "Aura Transcribe"),
        JsonReportRenderer("dependencies.json", false),
        TextReportRenderer("THIRD-PARTY-NOTICES.txt")
    )
}
