import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.changestree"
version = "0.5.17"

val targetWebStormVersion = "2026.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        val localIdePath = providers.gradleProperty("localIdePath")
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            webstorm(targetWebStormVersion)
        }
        bundledModule("intellij.platform.vcs.impl")
        bundledPlugin("Git4Idea")
        pluginVerifier("1.411")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

intellijPlatform {
    pluginConfiguration {
        name = "Smart Changes"
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
        description = """
<p>Smart Changes adds a focused view of local changes to the Commit tool window in WebStorm, helping you navigate large change sets and prepare commits.</p>
<ul>
    <li>Sort files by name, type, path, or modification time, and optionally group them by file extension.</li>
    <li>Hide unrelated files from the main list, then review or restore them in a separate hidden view. Hidden paths and view preferences are saved per project.</li>
    <li>See separate Staged and Unstaged rows when a Git file has changes in both areas. Use checkboxes to stage or unstage changes through the IDE's native Git actions.</li>
    <li>Open diffs, copy file paths, and access native VCS context actions with familiar file icons and status colors.</li>
    <li>Write a commit message and start the native Commit or Commit and Push workflow from the same panel.</li>
</ul>
<p>The view refreshes automatically as VCS changes are reported. Git staging support follows the IDE's staging-area setting; standard VCS change lists remain available when staging is disabled.</p>
<p>Hiding files only affects the Smart Changes view: it does not modify files or change their staging state. If hidden files are already staged, Smart Changes warns you before opening the native commit workflow.</p>
        """.trimIndent()
        changeNotes = "Fix panel disposal, mixed-VCS commit selection, secondary mouse clicks, and workspace settings validation."
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.WebStorm, targetWebStormVersion)
        }
    }
}

// Runs the threading regression without starting an IDE.
tasks.register<JavaExec>("testCommitCallback") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["test"].compileClasspath
    mainClass.set("dev.changestree.smartchanges.commit.PreparationCallbackTest")
}

tasks.test {
    // These regression tests use platform data types without booting an IDE.
    classpath += sourceSets["test"].compileClasspath
}

// PluginLogo also supports icons in the installed plugin directory before scanning JARs.
tasks.buildPlugin {
    from("src/main/resources/META-INF") {
        include("pluginIcon*.svg")
        into("META-INF")
    }
}
