dependencies {
    implementation(project(":core"))
    implementation(project(":world"))
}

fun evalTask(name: String, arg: String, text: String) = tasks.register<JavaExec>(name) {
    description = text
    group = "evaluation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fraudguard.eval.Evaluate")
    args(arg)
    workingDir = rootDir
    maxHeapSize = "3g"
}

evalTask("evaluate", "dev", "Runs every design on the development seeds and writes reports/dev.json.")
evalTask("heldout", "heldout", "Runs the held-out seeds once; a second run refuses.")
evalTask("readme", "readme", "Writes REPORT.md and the README results block from reports/.")
evalTask("gate", "gate", "Fails if a report misses a threshold in config/gates.json.")
evalTask("exportModel", "export-model", "Trains the guard's model on the first development seed's history and writes models/risk-model.json.")
