package com.toast.worldeditasync;

import java.util.UUID;

/**
 * 一次引擎編輯操作的完整記錄，完全獨立於 WorldEdit 原生的 LocalSession/ChangeSet。
 * 不呼叫、不依賴任何 WorldEdit 原生 history/undo API。
 */
public final class EditRecord {

    final UUID actor;
    final String worldKey;
    final long timestamp;
    final ChangeBuffer changes;

    EditRecord(UUID actor, String worldKey, ChangeBuffer changes) {
        this.actor = actor;
        this.worldKey = worldKey;
        this.timestamp = System.currentTimeMillis();
        this.changes = changes;
    }
}
