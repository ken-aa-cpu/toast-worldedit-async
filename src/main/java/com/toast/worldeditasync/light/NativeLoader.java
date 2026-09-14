package com.toast.worldeditasync.light;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 從打包進 jar 的 {@code /natives/<平台>/} 資源解壓 Rust 光罩計算池的動態庫並載入。
 * 沿用 {@code 概念驗證/02-rust-jni-addon-template} 已驗證過的模式（跨平台偵測 +
 * 解壓到暫存快取目錄 + {@code System.load}），只是從 Kotlin/NeoForge 環境搬到這個
 * Bukkit 插件，邏輯不變。
 */
final class NativeLoader {

    private static volatile boolean loaded = false;
    private static volatile boolean loadAttempted = false;
    private static volatile Throwable loadFailure = null;

    private NativeLoader() {
    }

    /** 回傳 true 才代表原生函式庫真的可用；呼叫端在 false 時必須 fallback，不可硬呼叫 JNI 方法。 */
    static synchronized boolean ensureLoaded(Logger logger) {
        if (loadAttempted) {
            return loaded;
        }
        loadAttempted = true;
        try {
            loadInternal(logger);
            loaded = true;
        } catch (Throwable t) {
            loadFailure = t;
            logger.warning("[WEPoC] 光罩計算池 native 函式庫載入失敗，光照計算將全程 fallback 回 vanilla checkBlock 路徑: " + t);
        }
        return loaded;
    }

    static Throwable getLoadFailure() {
        return loadFailure;
    }

    private static void loadInternal(Logger logger) throws IOException {
        String libName = "wepoc_light_native";
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String platformFolder;
        String fileName;
        if (osName.contains("win")) {
            platformFolder = "windows-x86_64";
            fileName = libName + ".dll";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            platformFolder = "macos-x86_64";
            fileName = "lib" + libName + ".dylib";
        } else {
            platformFolder = "linux-x86_64";
            fileName = "lib" + libName + ".so";
        }

        String resourcePath = "/natives/" + platformFolder + "/" + fileName;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("jar 資源裡找不到 " + resourcePath);
            }
            File cacheDir = new File(System.getProperty("java.io.tmpdir"), "wepoc_native_cache");
            cacheDir.mkdirs();
            File tempFile = new File(cacheDir, libName + "_" + System.currentTimeMillis() + fileExtension(fileName));
            tempFile.deleteOnExit();
            Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.load(tempFile.getAbsolutePath());
            logger.info("[WEPoC] 光罩計算池 native 函式庫載入成功: " + tempFile.getAbsolutePath());
        }
    }

    private static String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }
}
