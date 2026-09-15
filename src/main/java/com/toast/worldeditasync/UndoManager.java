package com.toast.worldeditasync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import com.toast.worldeditasync.light.RustLightEngine;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_21_R1.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 完全自建的 undo/redo：不呼叫、不依賴任何 WorldEdit 原生 history/undo API。
 * 規格書第 5 節硬性要求（2026-09-12/13 由委託人拍板，不受崩潰根因診斷結果影響）。
 *
 * 每個玩家一份 undo 堆疊、一份 redo 堆疊（上限 {@link #getMaxStackPerPlayer()}，可用
 * {@link #setMaxStackPerPlayer(int)} 調整，未來應改接正式 config.yml）。
 * 正向編輯、undo、redo 三者都走同一套「每 tick 限時分批」落地流程，每 tick 的時間預算依
 * {@link SpeedTier}（固定三檔或 {@link AutoThrottleController} 動態算出的 auto 模式）決定，
 * 不會為了任何一種操作走同步硬寫。同一玩家的操作嚴格按送出順序排隊：若上一個操作
 * （不管是正向編輯還是 undo/redo）還沒落地完，新的 //undo 只會排隊等它跑完，不會搶著跑。
 */
public final class UndoManager {

    private static final int PROGRESS_CHECK_INTERVAL = 64;

    private enum ActionType { FORWARD, UNDO, REDO }

    /**
     * 規格書 3.2 節「自動模式必須同時控制兩件事：主執行緒每 tick 的方塊寫入預算，
     * 以及丟進光照引擎排隊的量／頻率」——原本收尾的 relight 是寫入迴圈跑完後一次性
     * 整包做完（見舊版 {@code relightBoundingBoxWithBuffer}），大範圍操作會在收尾那一刻
     * 爆量，抵銷掉寫入階段節流的效果。現在拆成 WRITE→RELIGHT 兩個階段，relight 也用
     * 跟寫入同一份「每 tick 預算」逐步消耗，可以跨多個 tick 完成。兩階段共用同一份
     * bounding box 資料（只在寫入全部完成、世界已是最終狀態後才開始 relight，避免
     * 2026-09-13 光照完整性驗證抓到的「鄰居還沒填完就 relight 導致內部殘留舊光值」問題）。
     *
     * 2026-09-15 大範圍操作改接 Rust 光罩計算池：RELIGHT 拆成三個子階段
     * GATHER（讀取整個 relight box 的方塊資料，budget 內完成，跟 WRITE 共用同一套跨 tick
     * 分批機制）→ WAIT（丟到背景執行緒後純等待，逾時或失敗會 fallback）→ APPLY（把算好的
     * 結果寫回，逐格 budget 限流）。box 太小（見 {@link #RUST_RELIGHT_MIN_VOLUME}）或計算池
     * 不可用/逾時/失敗，一律 fallback 回舊版逐格 {@code checkBlock}（{@code Phase.RELIGHT}）
     * ——這條路徑正確性已經過完整驗證，永遠是安全網。
     */
    private enum Phase { WRITE, RELIGHT, RELIGHT_GATHER, RELIGHT_WAIT, RELIGHT_APPLY }

    private static final class QueuedAction {
        final ActionType type;
        final EditRecord forwardRecord;
        final ServerLevel level;
        final SpeedTier tier;

        QueuedAction(ActionType type, EditRecord forwardRecord, ServerLevel level, SpeedTier tier) {
            this.type = type;
            this.forwardRecord = forwardRecord;
            this.level = level;
            this.tier = tier;
        }
    }

    private static final class Job {
        final ActionType type;
        final EditRecord record;
        final ServerLevel level;
        final SpeedTier tier;
        int writeIndex = 0;
        Phase phase = Phase.WRITE;
        Iterator<BlockPos> relightIterator;
        int touched = 0;
        int relightTotal = 0;
        int relightDone = 0;
        long lastActionBarMillis = 0L;
        boolean relightFinished = false;

        // Rust 光罩計算池的中繼狀態，只有 phase 進到 RELIGHT_GATHER 之後才會用到。
        int rlMinX, rlMinY, rlMinZ, rlWidth, rlHeight, rlLength;
        byte[] rlOpacity;
        byte[] rlBlockSeed;
        byte[] rlSkySeed;
        int gatherIndex = 0;
        int applyIndex = 0;
        long rustDispatchMillis;
        volatile boolean rustDone = false;
        volatile boolean rustSucceeded = false;
        volatile RustLightEngine.Result rustResult;
        Map<Long, DataLayer> pendingBlockLayers;
        Map<Long, DataLayer> pendingSkyLayers;
        Map<Long, Boolean> blockSectionReady;
        Map<Long, Boolean> skySectionReady;

        Job(ActionType type, EditRecord record, ServerLevel level, SpeedTier tier) {
            this.type = type;
            this.record = record;
            this.level = level;
            this.tier = tier;
        }
    }

    /**
     * box 體積低於這個門檻就直接走舊版逐格 {@code checkBlock}，不值得付出 Rust 派工／JNI
     * 陣列搬運的固定成本——小範圍操作 checkBlock 本來就很快，計算池的優勢只在大範圍才會
     * 體現。門檻本身沒有實測校準過，先用一個保守的量級（4 萬格，大約一個 34x34x34 的方塊）
     * 卡住，之後有真實效能數據再調整。
     */
    private static final long RUST_RELIGHT_MIN_VOLUME = 40_000L;
    /**
     * 見 {@link RustLightEngine} 類別註解「呼叫端規則」：呼叫端要自己控制逾時，Rust 端算得
     * 異常久也不能拖住整個操作。逾時就放棄這批，fallback 回逐格 checkBlock（該 box 這次直接
     * 認賠用慢路徑，不重試——避免同一批操作反覆卡在逾時->重派工的迴圈）。
     */
    private static final long RUST_RELIGHT_TIMEOUT_MILLIS = 20_000L;

    private final JavaPlugin plugin;
    private final Map<UUID, Deque<EditRecord>> undoStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<EditRecord>> redoStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Job> currentJobs = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<QueuedAction>> pendingQueues = new ConcurrentHashMap<>();
    private final AutoThrottleController autoThrottle;
    private volatile int maxStackPerPlayer = 15;
    /**
     * 2026-09-15：{@link PasteExecutor} 自己接管 `//paste` 之後，COPY 階段（把 clipboard
     * 內容轉成 {@link ChangeBuffer}）本身也是跨很多 tick 分批完成，完成後才會呼叫
     * {@link #enqueueForward}——這段期間這裡的 {@code currentJobs} 完全看不到這個玩家有
     * 工作在跑（因為還沒交過來）。若玩家這時候緊接著送出 //undo，{@link #requestUndo} 會
     * 立刻把請求排進 {@code pendingQueues}，而 {@link #processPlayer} 原本一看到
     * {@code currentJobs.get(actor)==null} 就會馬上開始處理它，等於在 COPY 還沒交棒之前
     * 就把 undo 堆疊最上面那筆（COPY 開始前的最近一筆）復原掉，正向編輯完成的順序就亂了。
     * 用這個集合讓 {@link PasteExecutor} 標記「這個 actor 現在有一個外部非同步工作正在跑，
     * 先別開始處理佇列」，COPY 完成、真正呼叫 {@link #enqueueForward} 之後再解除標記
     * ——跟 {@code currentJobs} 佔位的效果一樣，只是佔位者不在這個類別管理的範圍內。
     */
    private final Set<UUID> externallyBusy = ConcurrentHashMap.newKeySet();

    public void markExternallyBusy(UUID actor) {
        externallyBusy.add(actor);
    }

    public void clearExternallyBusy(UUID actor) {
        externallyBusy.remove(actor);
    }

    public UndoManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.autoThrottle = new AutoThrottleController(plugin.getLogger());
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** `/wepoc stats` 等診斷指令用，讓管理員在自動模式下看得到目前動態預算。 */
    public long getAutoBudgetNanos() {
        return autoThrottle.getCurrentBudgetNanos();
    }

    public int getMaxStackPerPlayer() {
        return maxStackPerPlayer;
    }

    public void setMaxStackPerPlayer(int max) {
        this.maxStackPerPlayer = Math.max(1, max);
    }

    public int getUndoDepth(UUID actor) {
        Deque<EditRecord> stack = undoStacks.get(actor);
        return stack == null ? 0 : stack.size();
    }

    public int getRedoDepth(UUID actor) {
        Deque<EditRecord> stack = redoStacks.get(actor);
        return stack == null ? 0 : stack.size();
    }

    /** 一次正向編輯（//set、//paste 等）落地完成後排進該玩家的佇列。 */
    public void enqueueForward(UUID actor, ServerLevel level, EditRecord record, SpeedTier tier) {
        if (record.changes.isEmpty()) {
            return;
        }
        pendingQueues.computeIfAbsent(actor, k -> new ArrayDeque<>())
                .addLast(new QueuedAction(ActionType.FORWARD, record, level, tier));
    }

    /**
     * 排入一次 undo 請求。若該玩家目前還有正向編輯（或前一個 undo/redo）尚未落地完，
     * 這個請求只會排在佇列尾端等待，不會打斷正在跑的工作，也不會現在就去 pop undo 堆疊
     * ——真正 pop 的時機是「輪到它開始執行」的那一刻，這樣才能保證 pop 到的是「已經真的
     * 落地完成」的最近一筆記錄，符合規格書第 5.3 節的順序要求。
     */
    public void requestUndo(UUID actor, ServerLevel level, SpeedTier tier) {
        pendingQueues.computeIfAbsent(actor, k -> new ArrayDeque<>())
                .addLast(new QueuedAction(ActionType.UNDO, null, level, tier));
    }

    public void requestRedo(UUID actor, ServerLevel level, SpeedTier tier) {
        pendingQueues.computeIfAbsent(actor, k -> new ArrayDeque<>())
                .addLast(new QueuedAction(ActionType.REDO, null, level, tier));
    }

    /**
     * 每 tick 開頭先把「上一個已完整結束的 tick」的真實 MSPT 餵給
     * {@link AutoThrottleController}——這一步是自動模式正確性的關鍵，見
     * {@link AutoThrottleController} 類別註解：訊號必須是整個伺服器 tick 的真實耗時
     * （含 vanilla 收 tick 尾端自動打包的 multi-block-change 封包、其他外掛/紅石等
     * 所有主執行緒工作），不能只看我們自己的寫入/relight 迴圈花了多少時間。
     */
    private void tick() {
        feedRealTickTimeToAutoThrottle();

        Set<UUID> actors = new HashSet<>(currentJobs.keySet());
        for (Map.Entry<UUID, Deque<QueuedAction>> e : pendingQueues.entrySet()) {
            if (!e.getValue().isEmpty()) {
                actors.add(e.getKey());
            }
        }
        for (UUID actor : actors) {
            try {
                processPlayer(actor);
            } catch (Throwable t) {
                plugin.getLogger().severe("[WEPoC] UndoManager 處理 " + actor + " 時發生例外: " + t);
                t.printStackTrace();
                currentJobs.remove(actor);
            }
        }
    }

    private void feedRealTickTimeToAutoThrottle() {
        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            long[] recent = server.getTickTimesNanos();
            if (recent.length == 0) {
                return;
            }
            long lastRealTickNanos = recent[(server.getTickCount() - 1 + recent.length) % recent.length];
            autoThrottle.advanceTick(lastRealTickNanos);
        } catch (Throwable t) {
            plugin.getLogger().warning("[WEPoC] 讀取真實 tick 耗時失敗，auto 模式這個 tick 暫時沿用上次的預算: " + t);
        }
    }

    private void processPlayer(UUID actor) {
        Job job = currentJobs.get(actor);
        if (job == null) {
            if (externallyBusy.contains(actor)) {
                return; // 見 externallyBusy 欄位註解：外部非同步工作還沒交棒，佇列先原地等待
            }
            Deque<QueuedAction> queue = pendingQueues.get(actor);
            if (queue == null || queue.isEmpty()) {
                return;
            }
            QueuedAction action = queue.pollFirst();
            job = startJob(actor, action);
            if (job == null) {
                return;
            }
            currentJobs.put(actor, job);
        }

        long budgetNanos = switch (job.tier) {
            case SLOW, MID, FAST -> job.tier.getFixedBudgetNanos();
            case AUTO -> autoThrottle.getCurrentBudgetNanos();
        };

        long start = System.nanoTime();
        boolean yielded = false;

        if (job.phase == Phase.WRITE) {
            ChangeBuffer changes = job.record.changes;
            while (job.writeIndex < changes.size()) {
                int i = job.writeIndex++;
                BlockPos pos = BlockPos.of(changes.positionAt(i));
                switch (job.type) {
                    case FORWARD, REDO -> applyChange(job.level, pos, changes.afterStateAt(i), changes.afterNbtAt(i));
                    case UNDO -> applyChange(job.level, pos, changes.beforeStateAt(i), changes.beforeNbtAt(i));
                }
                job.touched++;
                if (job.touched % PROGRESS_CHECK_INTERVAL == 0 && System.nanoTime() - start > budgetNanos) {
                    yielded = true;
                    break;
                }
            }
            if (!yielded) {
                beginRelightPhase(job, changes);
            }
        }

        if (!yielded && job.phase == Phase.RELIGHT) {
            while (job.relightIterator.hasNext()) {
                job.level.getLightEngine().checkBlock(job.relightIterator.next());
                job.relightDone++;
                if (job.relightDone % PROGRESS_CHECK_INTERVAL == 0 && System.nanoTime() - start > budgetNanos) {
                    yielded = true;
                    break;
                }
            }
            if (!yielded) {
                job.relightFinished = true;
            }
        }

        if (!yielded && job.phase == Phase.RELIGHT_GATHER) {
            yielded = !gatherRustLightGrid(job, start, budgetNanos);
            if (!yielded) {
                dispatchRustCompute(job);
                job.phase = Phase.RELIGHT_WAIT;
            }
        }

        if (!yielded && job.phase == Phase.RELIGHT_WAIT) {
            if (job.rustDone) {
                if (job.rustSucceeded) {
                    beginRelightApply(job);
                } else {
                    plugin.getLogger().warning("[WEPoC] 光罩計算池這批失敗，fallback 回逐格 checkBlock");
                    fallbackToCheckBlockRelight(job);
                }
            } else if (System.currentTimeMillis() - job.rustDispatchMillis > RUST_RELIGHT_TIMEOUT_MILLIS) {
                plugin.getLogger().warning("[WEPoC] 光罩計算池逾時（>" + RUST_RELIGHT_TIMEOUT_MILLIS + "ms），fallback 回逐格 checkBlock");
                fallbackToCheckBlockRelight(job);
            } else {
                yielded = true; // 純等待背景執行緒，這個 tick 不做事
            }
        }

        if (!yielded && job.phase == Phase.RELIGHT_APPLY) {
            yielded = !applyRustLightResult(job, start, budgetNanos);
            if (!yielded) {
                flushPendingLightLayers(job);
                job.relightFinished = true;
                job.rlOpacity = null;
                job.rlBlockSeed = null;
                job.rlSkySeed = null;
                job.rustResult = null;
            }
        }

        autoThrottle.recordOwnContribution(System.nanoTime() - start);
        maybeSendProgressActionBar(actor, job);

        if (yielded) {
            return; // 讓出這個 tick，job 還留在 currentJobs，下一 tick 從目前 phase/iterator 位置繼續
        }
        if (job.relightFinished) {
            finishJob(actor, job);
            currentJobs.remove(actor);
        }
    }

    /**
     * WRITE 完成後決定 RELIGHT 怎麼跑：box 太小或計算池不可用一律走舊版逐格 checkBlock
     * （安全網，正確性已驗證過）；夠大且可用才進 RELIGHT_GATHER 準備丟給 Rust。
     */
    private void beginRelightPhase(Job job, ChangeBuffer touched) {
        if (touched.isEmpty()) {
            job.phase = Phase.RELIGHT;
            job.relightIterator = Collections.emptyIterator();
            job.relightTotal = 0;
            return;
        }
        int[] box = computeRelightBox(touched);
        long volume = (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
        boolean rustEligible = volume >= RUST_RELIGHT_MIN_VOLUME && volume <= Integer.MAX_VALUE
                && RustLightEngine.isAvailable(plugin.getLogger());
        if (!rustEligible) {
            job.phase = Phase.RELIGHT;
            job.relightIterator = new BoxIterator(box[0], box[1], box[2], box[3], box[4], box[5]);
            job.relightTotal = (int) volume;
            return;
        }
        job.rlMinX = box[0];
        job.rlMinY = box[1];
        job.rlMinZ = box[2];
        job.rlWidth = box[3] - box[0] + 1;
        job.rlHeight = box[4] - box[1] + 1;
        job.rlLength = box[5] - box[2] + 1;
        int total = job.rlWidth * job.rlHeight * job.rlLength;
        job.rlOpacity = new byte[total];
        job.rlBlockSeed = new byte[total];
        job.rlSkySeed = new byte[total];
        job.gatherIndex = 0;
        job.relightTotal = total;
        job.relightDone = 0;
        job.phase = Phase.RELIGHT_GATHER;
    }

    /** 逐格讀取 relight box 內的擋光值＋種子值，跟舊版 checkBlock 迴圈共用同一套 budget 節流。 */
    private boolean gatherRustLightGrid(Job job, long start, long budgetNanos) {
        int width = job.rlWidth, height = job.rlHeight, length = job.rlLength;
        int total = width * height * length;
        var blockListener = job.level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        var skyListener = job.level.getLightEngine().getLayerListener(LightLayer.SKY);
        while (job.gatherIndex < total) {
            int idx = job.gatherIndex++;
            int lz = idx % length;
            int rem = idx / length;
            int ly = rem % height;
            int lx = rem / height;
            BlockPos pos = new BlockPos(job.rlMinX + lx, job.rlMinY + ly, job.rlMinZ + lz);
            BlockState state = job.level.getBlockState(pos);
            job.rlOpacity[idx] = (byte) state.getLightBlock(job.level, pos);
            boolean boundary = lx == 0 || ly == 0 || lz == 0
                    || lx == width - 1 || ly == height - 1 || lz == length - 1;
            if (boundary) {
                job.rlBlockSeed[idx] = (byte) blockListener.getLightValue(pos);
                job.rlSkySeed[idx] = (byte) skyListener.getLightValue(pos);
            } else {
                int emission = state.getLightEmission();
                if (emission > 0) {
                    job.rlBlockSeed[idx] = (byte) emission;
                    job.rlSkySeed[idx] = -1;
                } else {
                    job.rlBlockSeed[idx] = -1;
                    job.rlSkySeed[idx] = -1;
                }
            }
            job.relightDone++;
            if (job.relightDone % PROGRESS_CHECK_INTERVAL == 0 && System.nanoTime() - start > budgetNanos) {
                return false;
            }
        }
        return true;
    }

    /**
     * 見 {@link RustLightEngine} 類別註解「呼叫端規則」：永遠不在主執行緒直接呼叫，一律丟
     * 背景執行緒，自己控制逾時。rustDone 最後才寫（volatile），保證 rustResult/rustSucceeded
     * 在其他執行緒觀察到 rustDone==true 時已經完整可見。
     */
    private void dispatchRustCompute(Job job) {
        job.rustDone = false;
        job.rustSucceeded = false;
        job.rustDispatchMillis = System.currentTimeMillis();
        final int w = job.rlWidth, h = job.rlHeight, l = job.rlLength;
        final byte[] opacity = job.rlOpacity, blockSeed = job.rlBlockSeed, skySeed = job.rlSkySeed;
        final Job target = job;
        new Thread(() -> {
            var resultOpt = RustLightEngine.tryComputeLight(plugin.getLogger(), w, h, l, opacity, blockSeed, skySeed);
            target.rustResult = resultOpt.orElse(null);
            target.rustSucceeded = resultOpt.isPresent();
            target.rustDone = true;
        }, "wepoc-relight-rust").start();
    }

    private void fallbackToCheckBlockRelight(Job job) {
        job.relightIterator = new BoxIterator(job.rlMinX, job.rlMinY, job.rlMinZ,
                job.rlMinX + job.rlWidth - 1, job.rlMinY + job.rlHeight - 1, job.rlMinZ + job.rlLength - 1);
        job.relightTotal = job.rlWidth * job.rlHeight * job.rlLength;
        job.relightDone = 0;
        job.rlOpacity = null;
        job.rlBlockSeed = null;
        job.rlSkySeed = null;
        job.rustResult = null;
        job.phase = Phase.RELIGHT;
    }

    private void beginRelightApply(Job job) {
        job.pendingBlockLayers = new HashMap<>();
        job.pendingSkyLayers = new HashMap<>();
        job.blockSectionReady = new HashMap<>();
        job.skySectionReady = new HashMap<>();
        job.applyIndex = 0;
        job.relightDone = 0;
        job.phase = Phase.RELIGHT_APPLY;
    }

    /** 把 Rust 算好的結果寫回：能排隊寫入的 section 就排隊，還沒被 vanilla 認證過有資料的 fallback 逐格 checkBlock。 */
    private boolean applyRustLightResult(Job job, long start, long budgetNanos) {
        int width = job.rlWidth, height = job.rlHeight, length = job.rlLength;
        int total = width * height * length;
        LevelLightEngine lightEngine = job.level.getLightEngine();
        RustLightEngine.Result result = job.rustResult;
        while (job.applyIndex < total) {
            int idx = job.applyIndex++;
            int lz = idx % length;
            int rem = idx / length;
            int ly = rem % height;
            int lx = rem / height;
            BlockPos pos = new BlockPos(job.rlMinX + lx, job.rlMinY + ly, job.rlMinZ + lz);
            applyRustLightForLayer(job, lightEngine, LightLayer.BLOCK, pos, result.blockLight()[idx],
                    job.pendingBlockLayers, job.blockSectionReady);
            applyRustLightForLayer(job, lightEngine, LightLayer.SKY, pos, result.skyLight()[idx],
                    job.pendingSkyLayers, job.skySectionReady);
            job.relightDone++;
            if (job.relightDone % PROGRESS_CHECK_INTERVAL == 0 && System.nanoTime() - start > budgetNanos) {
                return false;
            }
        }
        return true;
    }

    /**
     * 見驗證報告「二點二十一」的實測發現：vanilla 的 queueSectionData 有一個 hasSection 閘門
     * ——只有這個 section 已經被引擎認證為 LIGHT_AND_DATA，排隊寫入才會真的生效，否則永遠
     * 卡在 pending 裡不生效也不報錯。剛生成/剛貼上、vanilla 還沒正式算過光的全新區域一律
     * fallback 回 checkBlock，讓 vanilla 自己把它推進 LIGHT_AND_DATA 狀態。
     */
    private void applyRustLightForLayer(Job job, LevelLightEngine lightEngine, LightLayer layer, BlockPos pos,
            byte value, Map<Long, DataLayer> pendingLayers, Map<Long, Boolean> readyCache) {
        SectionPos sectionPos = SectionPos.of(pos);
        long sectionKey = sectionPos.asLong();
        Boolean ready = readyCache.get(sectionKey);
        if (ready == null) {
            ready = lightEngine.getDebugSectionType(layer, sectionPos) == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
            readyCache.put(sectionKey, ready);
        }
        if (!ready) {
            lightEngine.checkBlock(pos);
            return;
        }
        DataLayer layerData = pendingLayers.computeIfAbsent(sectionKey, k -> {
            DataLayer existing = lightEngine.getLayerListener(layer).getDataLayerData(sectionPos);
            return existing != null ? existing.copy() : new DataLayer();
        });
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        layerData.set(lx, ly, lz, value & 0xF);
    }

    private void flushPendingLightLayers(Job job) {
        LevelLightEngine lightEngine = job.level.getLightEngine();
        for (Map.Entry<Long, DataLayer> e : job.pendingBlockLayers.entrySet()) {
            lightEngine.queueSectionData(LightLayer.BLOCK, SectionPos.of(e.getKey()), e.getValue());
        }
        for (Map.Entry<Long, DataLayer> e : job.pendingSkyLayers.entrySet()) {
            lightEngine.queueSectionData(LightLayer.SKY, SectionPos.of(e.getKey()), e.getValue());
        }
        job.pendingBlockLayers = null;
        job.pendingSkyLayers = null;
        job.blockSectionReady = null;
        job.skySectionReady = null;
    }

    /** 只算 box 邊界（不建立 iterator），供 {@link #beginRelightPhase} 判斷體積用。 */
    private static int[] computeRelightBox(ChangeBuffer touched) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < touched.size(); i++) {
            BlockPos pos = BlockPos.of(touched.positionAt(i));
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new int[] {
                minX - RELIGHT_BUFFER, minY - RELIGHT_BUFFER, minZ - RELIGHT_BUFFER,
                maxX + RELIGHT_BUFFER, maxY + RELIGHT_BUFFER, maxZ + RELIGHT_BUFFER
        };
    }

    private static final long ACTION_BAR_MIN_INTERVAL_MILLIS = 400L;

    /**
     * 規格書第 4 節建議事項：「自動模式下建議透過 actionbar 或聊天訊息回報進度，因完成
     * 時間不固定，避免使用者誤以為卡住」。原本只是建議、非強制，但 2026-09-13 auto 模式
     * 真實背景負載實測（見驗證報告「二點十」）已經證實：大範圍操作在自動模式遇到真實
     * 高負載時，預算會被壓在地板值長達數十秒，這段時間如果玩家只看得到「指令已送出」
     * 沒有任何後續回饋，很容易誤以為卡死——這已經是有實測數據撐著的真實需求，不是
     * 錦上添花，所以不限於 auto 模式，固定三檔的大範圍操作一樣會受益，一併處理。
     * 節流到每 {@link #ACTION_BAR_MIN_INTERVAL_MILLIS} 才送一次，避免洗版。
     */
    private void maybeSendProgressActionBar(UUID actor, Job job) {
        long now = System.currentTimeMillis();
        if (now - job.lastActionBarMillis < ACTION_BAR_MIN_INTERVAL_MILLIS) {
            return;
        }
        job.lastActionBarMillis = now;
        Player player = Bukkit.getPlayer(actor);
        if (player == null) {
            return;
        }
        String phaseLabel;
        int done;
        int total;
        switch (job.phase) {
            case WRITE -> {
                phaseLabel = "寫入";
                done = job.touched;
                total = job.record.changes.size();
            }
            case RELIGHT_GATHER -> {
                phaseLabel = "光罩計算池-讀取";
                done = job.relightDone;
                total = job.relightTotal;
            }
            case RELIGHT_WAIT -> {
                phaseLabel = "光罩計算池-運算中";
                done = job.relightTotal; // 純等待背景執行緒，沒有逐格進度可回報，先顯示成已完成讀取
                total = job.relightTotal;
            }
            case RELIGHT_APPLY -> {
                phaseLabel = "光罩計算池-寫回";
                done = job.relightDone;
                total = job.relightTotal;
            }
            default -> {
                phaseLabel = "重新算光";
                done = job.relightDone;
                total = job.relightTotal;
            }
        }
        int percent = total > 0 ? (int) (100L * done / total) : 100;
        String tierLabel = job.tier == SpeedTier.AUTO
                ? String.format(Locale.ROOT, "auto(%.1fms)", autoThrottle.getCurrentBudgetNanos() / 1_000_000.0)
                : job.tier.name().toLowerCase(Locale.ROOT);
        String message = String.format(Locale.ROOT, "[小木斧] %s %d/%d (%d%%) | 檔位=%s",
                phaseLabel, done, total, percent, tierLabel);
        // 這台 Arclight build 實際載入的 org.bukkit.entity.Player 是它自帶的 CraftBukkit 相容層版本，
        // 不含 Paper 的 Adventure Audience 擴充（沒有 sendActionBar(Component)），改用任何 Spigot 系
        // 分支都通用的舊版 API。
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(message));
    }

    private static final int RELIGHT_BUFFER = 2;

    /**
     * 規格書第 2 節階段三「收尾」：對整個受影響 bounding box + 緩衝圈做一次統一 relight。
     *
     * 實測發現（2026-09-13 光照完整性驗證）：分批寫入期間逐格呼叫 checkBlock 並不夠——
     * 對一個座標呼叫 checkBlock 時，若它的鄰居當下還沒填完（still 分批中），算出來的光值
     * 會被鎖住，之後鄰居補齊了也沒有東西會回頭重新觸發它，導致大範圍實心填充的內部殘留
     * 填充前的舊光值（例如整片本該全暗的內部仍讀到天空光 15）。此外緊鄰編輯範圍外側、
     * 理論上會被連帶影響但沒被直接寫入的「緩衝圈」也會漏算。解法：等這次操作的所有寫入
     * 都真正落地完成後，對整個 bounding box 再往外擴一圈（{@link #RELIGHT_BUFFER}）統一
     * 重新算光一次，讓光照引擎用「最終完整狀態」重算，而不是在填充過程中的中間狀態就算數
     * ——{@link #beginRelightPhase} 決定這一步是走 Rust 計算池還是舊版逐格 checkBlock。
     */

    /** 對一個立方體範圍逐格產生 {@link BlockPos}，不預先展開成 List——大範圍 relight 不會佔用額外記憶體。 */
    private static final class BoxIterator implements Iterator<BlockPos> {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private int x, y, z;
        private boolean has;

        BoxIterator(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.x = minX;
            this.y = minY;
            this.z = minZ;
            this.has = minX <= maxX && minY <= maxY && minZ <= maxZ;
        }

        @Override
        public boolean hasNext() {
            return has;
        }

        @Override
        public BlockPos next() {
            BlockPos pos = new BlockPos(x, y, z);
            z++;
            if (z > maxZ) {
                z = minZ;
                y++;
                if (y > maxY) {
                    y = minY;
                    x++;
                    if (x > maxX) {
                        has = false;
                    }
                }
            }
            return pos;
        }
    }

    private Job startJob(UUID actor, QueuedAction action) {
        switch (action.type) {
            case FORWARD:
                return new Job(ActionType.FORWARD, action.forwardRecord, action.level, action.tier);
            case UNDO: {
                Deque<EditRecord> stack = undoStacks.get(actor);
                if (stack == null || stack.isEmpty()) {
                    notifyPlayer(actor, "[WEPoC] 沒有可復原的操作（引擎自製 undo 堆疊是空的）");
                    return null;
                }
                return new Job(ActionType.UNDO, stack.pollFirst(), action.level, action.tier);
            }
            case REDO: {
                Deque<EditRecord> stack = redoStacks.get(actor);
                if (stack == null || stack.isEmpty()) {
                    notifyPlayer(actor, "[WEPoC] 沒有可重做的操作");
                    return null;
                }
                return new Job(ActionType.REDO, stack.pollFirst(), action.level, action.tier);
            }
            default:
                return null;
        }
    }

    private void finishJob(UUID actor, Job job) {
        switch (job.type) {
            case FORWARD -> {
                pushCapped(undoStacks, actor, job.record);
                redoStacks.remove(actor); // 新編輯落地後，舊的 redo 歷史失效（標準 undo/redo 語意）
                notifyPlayer(actor, "[WEPoC] 編輯已落地並記入 undo 堆疊（" + job.record.changes.size()
                        + " 格，undo 深度=" + getUndoDepth(actor) + "）");
            }
            case UNDO -> {
                pushCapped(redoStacks, actor, job.record);
                notifyPlayer(actor, "[WEPoC] Undo 完成（" + job.record.changes.size()
                        + " 格已還原，undo 深度=" + getUndoDepth(actor) + " redo 深度=" + getRedoDepth(actor) + "）");
            }
            case REDO -> {
                pushCapped(undoStacks, actor, job.record);
                notifyPlayer(actor, "[WEPoC] Redo 完成（" + job.record.changes.size()
                        + " 格，undo 深度=" + getUndoDepth(actor) + " redo 深度=" + getRedoDepth(actor) + "）");
            }
        }
    }

    private void pushCapped(Map<UUID, Deque<EditRecord>> stacks, UUID actor, EditRecord record) {
        Deque<EditRecord> stack = stacks.computeIfAbsent(actor, k -> new ArrayDeque<>());
        stack.addFirst(record);
        while (stack.size() > maxStackPerPlayer) {
            stack.removeLast();
        }
    }

    private void notifyPlayer(UUID actor, String msg) {
        Player p = Bukkit.getPlayer(actor);
        if (p != null) {
            p.sendMessage(msg);
        }
        plugin.getLogger().info("[WEPoC] (" + actor + ") " + msg);
    }

    /**
     * 唯一的實際落地寫入路徑，正向/undo/redo 三者共用。
     *
     * 2026-09-13 光照完整性驗證發現：原本直接呼叫 {@code LevelChunkSection#setBlockState}
     * （section 底層 palette，繞過 {@code Level.setBlock} 整條管線）雖然不會崩潰、效能極佳，
     * 但也繞過了 {@code LevelChunk#setBlockState} 內部對 heightmap（天空光遮蔽高度）與
     * light section「是否為空」bookkeeping 的更新——導致光照引擎誤以為該欄位還是空的，
     * 大範圍實心填充後內部仍讀到滿天空光。改呼叫 {@code LevelChunk#setBlockState}
     * （比 section 低階寫入高一層，仍然跳過 {@code Level.setBlock} 的鄰居通知/紅石/物理），
     * 這樣 heightmap 與 light section 狀態都會正確更新，讓後續 {@link #beginRelightPhase}
     * 產生的 RELIGHT 階段能算出正確結果。
     */
    static void applyChange(ServerLevel level, BlockPos pos, BlockState targetState, CompoundTag targetNbt) {
        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        int sectionIndex = chunk.getSectionIndex(pos.getY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return;
        }
        chunk.setBlockState(pos, targetState, false);

        if (targetState.getBlock() instanceof EntityBlock entityBlock) {
            BlockEntity be = targetNbt != null
                    ? BlockEntity.loadStatic(pos, targetState, targetNbt, level.registryAccess())
                    : entityBlock.newBlockEntity(pos, targetState);
            if (be != null) {
                level.setBlockEntity(be);
            }
        } else {
            level.removeBlockEntity(pos);
        }

        level.getLightEngine().checkBlock(pos);
        level.sendBlockUpdated(pos, targetState, targetState, 3);
    }
}
