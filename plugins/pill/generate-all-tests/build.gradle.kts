import org.jetbrains.kotlin.pill.PillExtension

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

pill {
    variant = PillExtension.Variant.IDE
}

val depenencyProjects = arrayOf(
    ":generators",
    ":pill:generate-compiler-tests"
)

dependencies {
    depenencyProjects.forEach {
        testCompile(projectTests(it))
        jpsTest(project(it, configuration = "jpsTest"))
    }

    testRuntimeOnly(files("${rootProject.projectDir}/dist/kotlinc/lib/kotlin-reflect.jar"))
}

sourceSets {
    "main" { }
    "test" { projectDefault() }
}

testsJar()