package com.toast.worldeditasync;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.metadata.EntityProperties;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.EntityFunction;
import com.sk89q.worldedit.function.RegionFunction;
import com.sk89q.worldedit.function.RegionMaskingFilter;
import com.sk89q.worldedit.function.biome.ExtentBiomeCopy;
import com.sk89q.worldedit.function.block.ExtentBlockCopy;
import com.sk89q.worldedit.function.entity.ExtentEntityCopy;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.MaskIntersection;
import com.sk89q.worldedit.function.mask.Masks;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_21_R1.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-09-15：{@code //paste} 自己接管的非同步版本。
 *
 * <p>背景：實測（56.7M 格城堡在 Arclight 上兩度觸發看門狗，見驗證報告「二點二十二」）證實
 * WorldEdit 原生 {@code //paste} 的問題不在我們自己的 RELIGHT/WRITE 分批機制——那些都跑得
 * 動，問題出在 <b>WorldEdit 自己的 {@code Operations.completeLegacy(operation)}</b>：
 * 它就是 {@code while (op != null) op = op.resume(...)}，而實際做事的
 * {@code RegionVisitor.resume()} 內部是 {@code for (pt : region) {...}} 一次跑完整個區域，
 * 完全不是真的可分段恢復，不管我們自己的引擎多能分批，這一步永遠會在呼叫執行緒上一次跑完。
 *
 * <p>解法：{@code PasteBuilder.build()}（組出 mask/transform/entity/biome 邏輯那段）用的
 * 全是公開類別（{@link ExtentBlockCopy}、{@link RegionMaskingFilter}、
 * {@link BlockTransformExtent}、各種 {@link Mask}），可以自己重新組一份完全一樣的管線，
 * 只是不透過 {@link RegionVisitor} 的一次性迴圈——改成自己拿 {@link Region} 的
 * {@code Iterator<BlockVector3>}，逐格呼叫同一個 {@link RegionFunction#apply}，跟
 * {@link UndoManager} 的 WRITE/RELIGHT 用同一套「每 tick budget」節流分批完成。這樣完全
 * 不用重新發明 mask/transform/實體複製的邏輯（照抄自 WorldEdit 7.3.7 反編譯原始碼），只是
 * 換掉「誰來驅動迭代」。
 *
 * <p>方塊數量通常遠大於 entity/biome 數量（entity/biome 都要額外加 {@code -e}/{@code -b}
 * 才會複製，預設不複製），所以只有方塊複製這段跨 tick 分批；entity/biome 複製完成後一次性
 * 同步跑完——這是刻意的簡化，如果之後遇到 entity/biome 數量誇張到本身也會卡住的結構，
 * 需要比照方塊複製一樣拆成分批。
 *
 * <p>2026-09-15「主腦＋打印機」平行化試驗，做完拆掉：曾經把方塊複製這段拆成背景執行緒池
 * 平行算（mask/transform/adapt）+ 主執行緒序列化寫入，理論上應該更快。實測用單一方塊
 * 立方體跟真城堡（數百種方塊）各測過 1 個打印機 vs 10 個打印機，COPY 階段耗時都在誤差
 * 範圍內幾乎一樣（城堡：1104.0s vs 1130.1s），平行化沒有帶來任何可量測的加速——即使先
 * 反編譯確認並繞開了 {@code BukkitAdapter.adapt} 內部的全域鎖（自己刻一個
 * {@code ConcurrentHashMap} 快取取代），結果依然沒變。委託人的原話「不是越多越好」：
 * 多了執行緒池、批次分派、{@code Future} 追蹤這些複雜度卻沒有實測到好處，值得拆掉——
 * 於是退回這個最初的單一序列化迴圈版本，維持簡單。真正有效的優化留著：
 * {@link WorldEditAsyncPlugin#stashPendingSizeHint} 讓 {@link ChangeBuffer} 用正確初始
 * 容量建立（見驗證報告「二點二十四」，這才是實測有效的那個修正）。
 */
public final class PasteExecutor {

    private static final int PROGRESS_CHECK_INTERVAL = 64;

    private static final class Job {
        final UUID actor;
        final ServerLevel level;
        final String worldKey;
        final SpeedTier tier;
        final EditSession editSession;
        final WEAsyncExtent wrapped;
        final RegionFunction blockFunction;
        final Iterator<BlockVector3> blockIterator;
        final int blockTotal;
        int blockDone = 0;

        final boolean copyEntities;
        final boolean copyBiomes;
        final Extent transformedSource;
        final Region region;
        final BlockVector3 from;
        final BlockVector3 to;
        final Transform transform;

        long lastActionBarMillis = 0L;

        Job(UUID actor, ServerLevel level, String worldKey, SpeedTier tier, EditSession editSession,
                WEAsyncExtent wrapped, RegionFunction blockFunction, Iterator<BlockVector3> blockIterator,
                int blockTotal, boolean copyEntities, boolean copyBiomes, Extent transformedSource,
                Region region, BlockVector3 from, BlockVector3 to, Transform transform) {
            this.actor = actor;
            this.level = level;
            this.worldKey = worldKey;
            this.tier = tier;
            this.editSession = editSession;
            this.wrapped = wrapped;
            this.blockFunction = blockFunction;
            this.blockIterator = blockIterator;
            this.blockTotal = blockTotal;
            this.copyEntities = copyEntities;
            this.copyBiomes = copyBiomes;
            this.transformedSource = transformedSource;
            this.region = region;
            this.from = from;
            this.to = to;
            this.transform = transform;
        }
    }

    private final WorldEditAsyncPlugin plugin;
    private final UndoManager undoManager;
    private final Map<UUID, Job> currentJobs = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Runnable>> pendingStarts = new ConcurrentHashMap<>();

    public PasteExecutor(WorldEditAsyncPlugin plugin, UndoManager undoManager) {
        this.plugin = plugin;
        this.undoManager = undoManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * 解析出來的旗標，對照 WorldEdit 7.3.7 {@code ClipboardCommands.paste} 反編譯結果，
     * 逐項比對過語意（見類別註解）：{@code -a} 略過空氣、{@code -v} 包含 structure_void、
     * {@code -o} 貼在原始座標、{@code -s} 貼完選取、{@code -n} 只選不貼（隱含 -s）、
     * {@code -e} 複製實體（預設不複製）、{@code -b} 複製生態域（預設不複製）、
     * {@code -m <mask>} 只貼符合 mask 的來源方塊。
     */
    private record PasteFlags(boolean ignoreAirBlocks, boolean pasteStructureVoid, boolean atOrigin,
            boolean selectPasted, boolean onlySelect, boolean pasteEntities, boolean pasteBiomes, String maskInput) {
    }

    private static PasteFlags parseFlags(String[] args, int startIndex) {
        boolean ignoreAirBlocks = false, pasteStructureVoid = false, atOrigin = false;
        boolean selectPasted = false, onlySelect = false, pasteEntities = false, pasteBiomes = false;
        String maskInput = null;
        for (int i = startIndex; i < args.length; i++) {
            String token = args[i];
            if (token.length() < 2 || token.charAt(0) != '-' || token.charAt(1) == '-') {
                continue; // 不是我們認得的單字元旗標格式，忽略（原生指令這裡會報語法錯，我們選擇寬容跳過）
            }
            for (int c = 1; c < token.length(); c++) {
                switch (token.charAt(c)) {
                    case 'a' -> ignoreAirBlocks = true;
                    case 'v' -> pasteStructureVoid = true;
                    case 'o' -> atOrigin = true;
                    case 's' -> selectPasted = true;
                    case 'n' -> onlySelect = true;
                    case 'e' -> pasteEntities = true;
                    case 'b' -> pasteBiomes = true;
                    case 'm' -> {
                        if (i + 1 < args.length) {
                            maskInput = args[++i];
                        }
                    }
                    default -> {
                        // 未知旗標字元，忽略——寬容優於在這裡自己重新實作完整的語法錯誤回報
                    }
                }
            }
        }
        return new PasteFlags(ignoreAirBlocks, pasteStructureVoid, atOrigin, selectPasted, onlySelect,
                pasteEntities, pasteBiomes, maskInput);
    }

    /**
     * {@code /wepoc}（實際上是攔截原生 {@code //paste}）的入口。args 是「//paste」之後的
     * 原始引數（旗標，檔位後綴已經在呼叫端剝掉）。
     */
    public void handlePasteCommand(Player player, String[] args, SpeedTier tier) {
        UUID actorId = player.getUniqueId();
        Runnable start = () -> doStart(player, args, tier);
        if (currentJobs.containsKey(actorId)) {
            pendingStarts.computeIfAbsent(actorId, k -> new ArrayDeque<>()).addLast(start);
            plugin.sendActionBar(player, "[WEPoC] 上一個 //paste 還在背景處理中，這次已排隊，會照順序接著跑。");
            return;
        }
        start.run();
    }

    private void doStart(Player player, String[] args, SpeedTier tier) {
        UUID actorId = player.getUniqueId();
        try {
            com.sk89q.worldedit.entity.Player weActor = BukkitAdapter.adapt(player);
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(player.getWorld());
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(weActor);

            ClipboardHolder holder;
            try {
                holder = session.getClipboard();
            } catch (Throwable t) {
                plugin.sendActionBar(player, "Your clipboard is empty. Use //copy first.");
                return;
            }
            Clipboard clipboard = holder.getClipboard();
            Transform transform = holder.getTransform();
            Region region = clipboard.getRegion();

            PasteFlags flags = parseFlags(args, 0);
            BlockVector3 to;
            try {
                to = flags.atOrigin() ? clipboard.getOrigin() : session.getPlacementPosition(weActor);
            } catch (Throwable t) {
                plugin.sendActionBar(player, "[WEPoC] //paste 失敗：" + t.getMessage());
                return;
            }

            if (flags.onlySelect()) {
                // 只選取不貼——不用經過我們整套非同步管線，直接照抄原生邏輯量體極小，
                // 對這個分支沒有效能疑慮，維持同步處理即可。
                applySelectionOnly(player, weWorld, session, holder, clipboard, region, to);
                return;
            }

            Extent transformedSource = new BlockTransformExtent(clipboard, transform);

            Mask combinedMask = Masks.alwaysTrue();
            if (flags.ignoreAirBlocks()) {
                Mask existing = new ExistingBlockMask(clipboard);
                combinedMask = combinedMask == Masks.alwaysTrue() ? existing : new MaskIntersection(combinedMask, existing);
            }
            if (!flags.pasteStructureVoid()) {
                Mask structureVoidMask = Masks.negate(new BlockTypeMask(clipboard, BlockTypes.STRUCTURE_VOID));
                combinedMask = combinedMask == Masks.alwaysTrue() ? structureVoidMask : new MaskIntersection(combinedMask, structureVoidMask);
            }
            if (flags.maskInput() != null) {
                try {
                    ParserContext parserContext = new ParserContext();
                    parserContext.setActor(weActor);
                    parserContext.setWorld(weWorld);
                    parserContext.setSession(session);
                    Mask userMask = WorldEdit.getInstance().getMaskFactory().parseFromInput(flags.maskInput(), parserContext);
                    combinedMask = combinedMask == Masks.alwaysTrue() ? userMask : new MaskIntersection(combinedMask, userMask);
                } catch (Throwable t) {
                    plugin.sendActionBar(player, "[WEPoC] //paste 的 -m 遮罩解析失敗，已忽略: " + t.getMessage());
                }
            }

            ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
            String worldKey = level.dimension().location().toString();

            // 建立正式 EditSession——會觸發跟原生指令完全一樣的 EditSessionEvent，
            // WorldEditAsyncPlugin#onEditSession 照常把 WEAsyncExtent 換上去，
            // 只是我們標記了 customPasteActor，讓它跳過「下一 tick 自動打包」那段
            // （那段假設是原生同步指令，COPY 階段這裡是跨很多 tick 才會完成）。
            // 見 WorldEditAsyncPlugin#stashPendingSizeHint 註解：這裡已經直接知道精確格數，
            // 不用像一般指令那樣用估計值，讓 WEAsyncExtent 的 ChangeBuffer 用正確容量建立。
            plugin.stashPendingSizeHint(actorId, tier, region.getVolume());
            plugin.markCustomPasteInFlight(actorId);
            EditSession editSession;
            WEAsyncExtent wrapped;
            try {
                editSession = session.createEditSession(weActor);
                wrapped = plugin.takeLastExtent(actorId);
            } finally {
                plugin.clearCustomPasteInFlight(actorId);
            }
            if (wrapped == null) {
                plugin.sendActionBar(player, "[WEPoC] //paste 失敗：引擎沒有接管到這次的 EditSession（engine 是否開啟？）");
                editSession.close();
                return;
            }

            ExtentBlockCopy blockCopy = new ExtentBlockCopy(transformedSource, clipboard.getOrigin(), editSession, to, transform);
            RegionFunction filteredFunction = new RegionMaskingFilter(combinedMask, blockCopy);

            undoManager.markExternallyBusy(actorId);
            Job job = new Job(actorId, level, worldKey, tier, editSession, wrapped, filteredFunction,
                    region.iterator(), (int) Math.min(Integer.MAX_VALUE, region.getVolume()),
                    flags.pasteEntities(), flags.pasteBiomes() && clipboard.hasBiomes(),
                    transformedSource, region, clipboard.getOrigin(), to, transform);
            currentJobs.put(actorId, job);
            plugin.sendActionBar(player, String.format(Locale.ROOT,
                    "[WEPoC] //paste 已接管，開始背景分批複製（%,d 格來源方塊）...", job.blockTotal));
            plugin.debugLog("PasteExecutor job 開始: actor=" + actorId + " tier=" + tier
                    + " 來源格數=" + job.blockTotal + " copyEntities=" + job.copyEntities
                    + " copyBiomes=" + job.copyBiomes);

            if (flags.selectPasted()) {
                // 選取更新本身量體極小（只是設定 CuboidRegionSelector），維持同步，跟原生一致。
                applyPasteSelection(player, weWorld, session, holder, clipboard, region, to);
            }
        } catch (Throwable t) {
            plugin.sendActionBar(player, "[WEPoC] //paste 發生未預期例外: " + t);
            plugin.getLogger().severe("[WEPoC] PasteExecutor.doStart 例外: " + t);
            t.printStackTrace();
        }
    }

    private void applySelectionOnly(Player player, com.sk89q.worldedit.world.World weWorld, LocalSession session,
            ClipboardHolder holder, Clipboard clipboard, Region region, BlockVector3 to) {
        applyPasteSelection(player, weWorld, session, holder, clipboard, region, to);
        plugin.sendActionBar(player, "已選取，未貼上。");
    }

    /** 照抄原生 {@code ClipboardCommands.paste} 的選取更新邏輯，量體小，同步處理無風險。 */
    private void applyPasteSelection(Player player, com.sk89q.worldedit.world.World weWorld, LocalSession session,
            ClipboardHolder holder, Clipboard clipboard, Region region, BlockVector3 to) {
        try {
            BlockVector3 clipboardOffset = clipboard.getRegion().getMinimumPoint().subtract(clipboard.getOrigin());
            com.sk89q.worldedit.math.Vector3 realTo = to.toVector3().add(holder.getTransform().apply(clipboardOffset.toVector3()));
            com.sk89q.worldedit.math.Vector3 max = realTo.add(holder.getTransform().apply(
                    region.getMaximumPoint().subtract(region.getMinimumPoint()).toVector3()));
            com.sk89q.worldedit.regions.selector.CuboidRegionSelector selector =
                    session.getRegionSelector(weWorld) instanceof com.sk89q.worldedit.regions.selector.ExtendingCuboidRegionSelector
                            ? new com.sk89q.worldedit.regions.selector.ExtendingCuboidRegionSelector(weWorld, realTo.toBlockPoint(), max.toBlockPoint())
                            : new com.sk89q.worldedit.regions.selector.CuboidRegionSelector(weWorld, realTo.toBlockPoint(), max.toBlockPoint());
            session.setRegionSelector(weWorld, selector);
            selector.learnChanges();
        } catch (Throwable t) {
            plugin.getLogger().warning("[WEPoC] //paste 更新選取範圍失敗（不影響貼上本身）: " + t);
        }
    }

    private void tick() {
        for (UUID actor : new HashSet<>(currentJobs.keySet())) {
            try {
                processJob(actor);
            } catch (Throwable t) {
                plugin.getLogger().severe("[WEPoC] PasteExecutor 處理 " + actor + " 時發生例外: " + t);
                t.printStackTrace();
                Job job = currentJobs.remove(actor);
                undoManager.clearExternallyBusy(actor);
                if (job != null) {
                    try {
                        job.editSession.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void processJob(UUID actor) {
        Job job = currentJobs.get(actor);
        if (job == null) {
            return;
        }
        long budgetNanos = switch (job.tier) {
            case SLOW, MID, FAST -> job.tier.getFixedBudgetNanos();
            case AUTO -> undoManager.getAutoBudgetNanos();
        };
        long start = System.nanoTime();
        boolean yielded = false;
        while (job.blockIterator.hasNext()) {
            BlockVector3 pt = job.blockIterator.next();
            try {
                job.blockFunction.apply(pt);
            } catch (WorldEditException e) {
                plugin.getLogger().warning("[WEPoC] //paste 複製 " + pt + " 失敗，跳過該格: " + e);
            }
            job.blockDone++;
            if (job.blockDone % PROGRESS_CHECK_INTERVAL == 0 && System.nanoTime() - start > budgetNanos) {
                yielded = true;
                break;
            }
        }
        maybeSendProgress(actor, job);
        if (yielded) {
            return;
        }
        finishJob(actor, job);
    }

    private static final long ACTION_BAR_MIN_INTERVAL_MILLIS = 400L;

    private void maybeSendProgress(UUID actor, Job job) {
        long now = System.currentTimeMillis();
        if (now - job.lastActionBarMillis < ACTION_BAR_MIN_INTERVAL_MILLIS) {
            return;
        }
        job.lastActionBarMillis = now;
        Player player = Bukkit.getPlayer(actor);
        if (player == null) {
            return;
        }
        int percent = job.blockTotal > 0 ? (int) (100L * job.blockDone / job.blockTotal) : 100;
        String message = String.format(Locale.ROOT, "[小木斧] //paste 複製 %,d/%,d (%d%%)",
                job.blockDone, job.blockTotal, percent);
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(message));
    }

    /** 方塊迭代跑完之後：entity/biome（若有要求）一次性同步跑完，然後交棒給 UndoManager。 */
    private void finishJob(UUID actor, Job job) {
        plugin.debugLog("PasteExecutor job COPY 完成: actor=" + actor + " 格數=" + job.blockTotal
                + "，交棒給 UndoManager 進入 WRITE/RELIGHT");
        try {
            if (job.copyBiomes) {
                ExtentBiomeCopy biomeCopy = new ExtentBiomeCopy(job.transformedSource, job.from, job.editSession, job.to, job.transform);
                for (BlockVector3 pt : job.region) {
                    try {
                        biomeCopy.apply(pt);
                    } catch (WorldEditException e) {
                        plugin.getLogger().warning("[WEPoC] //paste 複製生態域 " + pt + " 失敗，跳過: " + e);
                    }
                }
            }
            if (job.copyEntities) {
                ExtentEntityCopy entityCopy = new ExtentEntityCopy(job.from.toVector3(), job.editSession, job.to.toVector3(), job.transform);
                List<Entity> entities = new ArrayList<>(job.transformedSource.getEntities(job.region));
                entities.removeIf(entity -> {
                    EntityProperties properties = entity.getFacet(EntityProperties.class);
                    return properties != null && !properties.isPasteable();
                });
                for (Entity entity : entities) {
                    try {
                        entityCopy.apply(entity);
                    } catch (WorldEditException e) {
                        plugin.getLogger().warning("[WEPoC] //paste 複製實體失敗，跳過: " + e);
                    }
                }
            }
        } finally {
            try {
                job.editSession.close();
            } catch (Throwable ignored) {
            }
            currentJobs.remove(actor);

            EditRecord record = new EditRecord(actor, job.worldKey, job.wrapped.getChanges());
            // 見 externallyBusy 欄位註解：先把這次正向編輯交棒進 UndoManager 的佇列，
            // 再解除忙碌標記——確保佇列裡任何在這之後才被處理的 undo/redo 請求，
            // 一定排在這筆正向編輯後面，順序不會亂。
            undoManager.enqueueForward(actor, job.level, record, job.tier);
            undoManager.clearExternallyBusy(actor);

            Player player = Bukkit.getPlayer(actor);
            if (player != null) {
                plugin.sendActionBar(player, String.format(Locale.ROOT,
                        "[WEPoC] //paste 複製完成（%,d 格），已交給引擎排隊落地。", job.blockDone));
            }

            Deque<Runnable> queue = pendingStarts.get(actor);
            if (queue != null) {
                Runnable next = queue.pollFirst();
                if (next != null) {
                    next.run();
                }
            }
        }
    }
}
