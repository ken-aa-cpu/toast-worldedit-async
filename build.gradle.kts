import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id("java")
}

group = "com.toast.worldeditasync"
version = "0.2.0"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://maven.enginehub.org/repo/") }
}

val serverDir = "../../../伺服器本體/主混端伺服器"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.7")
    // Arclight 執行期實際載入的 Mojmap NMS 類別（供編譯期參照，僅 compileOnly，不打包）
    compileOnly(files("$serverDir/libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar"))
    // Arclight 產生的 CraftBukkit 相容層（CraftWorld/CraftChunk 等）
    compileOnly(files("$serverDir/.arclight/mod_file/arclight-1.21.1-1.0.2-SNAPSHOT-166b987.jar"))
}

val targetJavaVersion = 21
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.jar {
    archiveBaseName.set("Toast_WorldEditAsync")
}

// ---------------------------------------------------------------------------
// 光罩計算池：Rust native 編譯與動態庫打包（沿用 02-rust-jni-addon-template 的模式）
// ---------------------------------------------------------------------------
val cargoBuildLight = tasks.register<Exec>("cargoBuildLight") {
    group = "build"
    description = "編譯光罩計算池的 Rust cdylib"
    workingDir = file("rust-core")
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("cmd", "/c", "cargo build --release")
    } else {
        commandLine("cargo", "build", "--release")
    }
}

val copyLightNatives = tasks.register<Copy>("copyLightNatives") {
    dependsOn(cargoBuildLight)
    group = "build"
    description = "把編譯好的光罩計算池動態庫複製進 resources，隨插件 jar 一起打包"
    from(file("rust-core/target/release")) {
        include("*.dll", "*.so", "*.dylib")
    }
    into(file("src/main/resources/natives/windows-x86_64"))
}

tasks.named("processResources") {
    dependsOn(copyLightNatives)
}
