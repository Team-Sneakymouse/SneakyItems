// Template-only build logic for Team-Sneakymouse/EmptyPlugin.
// Applied by build.gradle.kts only while this file exists. scripts/rename-template.sh deletes it,
// so plugins created from the template start without any of these checks.

val templateRenameSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run scripts/rename-template.sh on a scratch copy and check nothing template-specific is left."
    workingDir = rootDir
    // Reuse publishPreflight's bash (Git Bash on Windows, not WSL).
    val bash = checkNotNull(tasks.named<Exec>("publishPreflight").get().executable)
    commandLine(bash, "scripts/rename-template.sh", "--self-test")
}

tasks.named("check") {
    dependsOn(templateRenameSmoke)
}
