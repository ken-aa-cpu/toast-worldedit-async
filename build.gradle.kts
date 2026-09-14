plugins {
    id("java")
}

group = "com.toast.worldeditasync"
version = "0.1.0"

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
