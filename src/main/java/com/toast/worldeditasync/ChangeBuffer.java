package com.toast.worldeditasync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 取代原本的 {@code Map<BlockPos, ChangeEntry>}：用扁平 primitive 陣列 + 開放定址雜湊索引
 * 記錄一次操作的全部方塊變更，避開「每格一顆 Java 物件」的記憶體開銷。
 *
 * 2026-09-15 委託人用 5676 萬格城堡壓測抓到的真正風險：原本的寫法每格要疊三層物件
 * （{@code BlockPos} 物件 + {@code ChangeEntry} 物件 + {@code HashMap.Entry} 物件），
 * 換算下來約 150+ bytes/格，56,764,020 格一次操作就要吃掉將近 9GB JVM heap——而且
 * {@code UndoManager} 每個玩家 undo 堆疊上限 15 筆，理論上可以疊到嚇死人的量，這個
 * 壓測之前一直沒被實測到過（先前壓測跑在 engine=off，完全沒經過這條路徑）。
 *
 * 改成扁平陣列後每格只要約 16~20 bytes（一個 {@code long} 座標 + 兩個 {@code int}
 * 全域 BlockState id），5676 萬格降到約 1GB，10 倍以上縮減，而且完全是 JVM heap 內的
 * primitive 陣列操作，不需要任何 off-heap/Rust 就先拿到這個大部分的收益。
 *
 * BlockState 用 vanilla 自己現成的全域整數 id（{@link Block#getId}/{@link Block#stateById}，
 * 公開靜態方法）打包，不需要另外自建 state↔id 對照表。真正帶 NBT 的方塊（方塊實體，
 * 是少數）另外用一個小型 side map 存，不影響絕大多數純方塊格的扁平化效果。
 */
final class ChangeBuffer {

    private long[] positions;
    private int[] beforeStateIds;
    private int[] afterStateIds;
    private int size;

    // 開放定址（linear probing）雜湊索引：packed position -> 陣列 index。
    private long[] slotKeys;
    private int[] slotIndex;
    private int slotMask;
    private static final long EMPTY_SLOT = Long.MIN_VALUE;

    private Map<Long, CompoundTag> beforeNbtByPos;
    private Map<Long, CompoundTag> afterNbtByPos;

    ChangeBuffer(int expectedSize) {
        int cap = Math.max(16, expectedSize);
        positions = new long[cap];
        beforeStateIds = new int[cap];
        afterStateIds = new int[cap];
        int slotCap = Integer.highestOneBit(Math.max(16, cap)) << 2;
        slotKeys = new long[slotCap];
        Arrays.fill(slotKeys, EMPTY_SLOT);
        slotIndex = new int[slotCap];
        slotMask = slotCap - 1;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /**
     * 2026-09-15 實測抓到的效能坑：一開始用 {@code Long.hashCode(packedPos)}（單純
     * XOR 折疊高低 32 bits）當雜湊值，對「依座標順序逐格插入」這種高度規律的資料
     * （貼上/填充操作正是如此，逐格巢狀迴圈）非常不友善——{@code BlockPos.asLong()}
     * 把 x/y/z 打包進固定位段，逐格遞增時很多 bit 位置完全不變，XOR 折疊後容易讓
     * 大量座標落在同一批 slot、開放定址線性探測退化成長長的碰撞鏈。5676 萬格的城堡
     * 實測：加了這個 mix 之前，recording 階段跑了 27 分鐘還沒完（換算不到原生路徑
     * 1/20 的速度，換算應是碰撞鏈占滿了大部分執行時間），懷疑就是這個。改用
     * splitmix64 風格的乘法+移位混合，讓每個位段都均勻影響最終雜湊值，解決規律輸入
     * 導致的病態碰撞。
     */
    private static long mixHash(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return x;
    }

    private int findSlot(long packedPos) {
        int slot = (int) mixHash(packedPos) & slotMask;
        while (slotKeys[slot] != EMPTY_SLOT && slotKeys[slot] != packedPos) {
            slot = (slot + 1) & slotMask;
        }
        return slot;
    }

    /** 這個座標在陣列中的 index；沒記錄過回 -1。 */
    int indexOf(long packedPos) {
        int slot = findSlot(packedPos);
        return slotKeys[slot] == EMPTY_SLOT ? -1 : slotIndex[slot];
    }

    long positionAt(int i) {
        return positions[i];
    }

    BlockState beforeStateAt(int i) {
        return Block.stateById(beforeStateIds[i]);
    }

    BlockState afterStateAt(int i) {
        return Block.stateById(afterStateIds[i]);
    }

    CompoundTag beforeNbtAt(int i) {
        return beforeNbtByPos == null ? null : beforeNbtByPos.get(positions[i]);
    }

    CompoundTag afterNbtAt(int i) {
        return afterNbtByPos == null ? null : afterNbtByPos.get(positions[i]);
    }

    private void growArraysIfNeeded() {
        if (size == positions.length) {
            int newCap = positions.length * 2;
            positions = Arrays.copyOf(positions, newCap);
            beforeStateIds = Arrays.copyOf(beforeStateIds, newCap);
            afterStateIds = Arrays.copyOf(afterStateIds, newCap);
        }
    }

    private void growSlotsIfNeeded() {
        if ((size + 1) > (slotKeys.length * 7L / 10L)) {
            int newCap = slotKeys.length * 2;
            slotKeys = new long[newCap];
            Arrays.fill(slotKeys, EMPTY_SLOT);
            slotIndex = new int[newCap];
            slotMask = newCap - 1;
            for (int i = 0; i < size; i++) {
                int slot = findSlot(positions[i]);
                slotKeys[slot] = positions[i];
                slotIndex[slot] = i;
            }
        }
    }

    /**
     * 第一次遇到這個座標：記下變更前狀態，並把變更後狀態暫設成同一個值（跟原本
     * {@code ChangeEntry} 建構式的語意一致，等真正的 {@link #recordAfter} 呼叫再覆寫）。
     */
    int recordFirstSeen(long packedPos, BlockState beforeState, CompoundTag beforeNbt) {
        growSlotsIfNeeded();
        growArraysIfNeeded();
        int idx = size++;
        positions[idx] = packedPos;
        beforeStateIds[idx] = Block.getId(beforeState);
        afterStateIds[idx] = beforeStateIds[idx];
        if (beforeNbt != null) {
            if (beforeNbtByPos == null) {
                beforeNbtByPos = new HashMap<>();
            }
            beforeNbtByPos.put(packedPos, beforeNbt);
            if (afterNbtByPos == null) {
                afterNbtByPos = new HashMap<>();
            }
            afterNbtByPos.put(packedPos, beforeNbt);
        }
        int slot = findSlot(packedPos);
        slotKeys[slot] = packedPos;
        slotIndex[slot] = idx;
        return idx;
    }

    void recordAfter(int idx, BlockState afterState, CompoundTag afterNbt) {
        afterStateIds[idx] = Block.getId(afterState);
        long pos = positions[idx];
        if (afterNbt != null) {
            if (afterNbtByPos == null) {
                afterNbtByPos = new HashMap<>();
            }
            afterNbtByPos.put(pos, afterNbt);
        } else if (afterNbtByPos != null) {
            afterNbtByPos.remove(pos);
        }
    }
}
