package com.toast.worldeditasync;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.toast.worldeditasync.light.RustLightEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_21_R1.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldEdit 混端非同步方塊引擎 PoC。
 *
 * - 直寫 LevelChunkSection + 手動 relight 的 Arclight 相容性、MinecraftServer tick 耗時介面
 *   已於第一階段驗證通過。
 * - {@code engine=on} 時，EditSessionEvent 於 BEFORE_HISTORY 階段換上 {@link WEAsyncExtent}，
 *   接管 WorldEdit 的方塊寫入（此 Arclight build 上 WorldEdit 原生路徑完全無法編輯）。
 * - {@code //undo}/{@code //redo} 一律攔截、導到 {@link UndoManager} 自建的 undo/redo，
 *   完全不呼叫 WorldEdit 原生 history/undo API（規格書第 5 節硬性要求，2026-09-13 拍板，
 *   不受先前崩潰根因診斷結果影響——當時的環境已確認被同機另一組高負載測試污染，
 *   淨室重測未能重現該崩潰）。
 */
public final class WorldEditAsyncPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private static final Set<String> UNDO_REDO_COMMANDS = Set.of(
            "//undo", "/undo", "//redo", "/redo");
    private static final Set<String> UNDO_COMMANDS = Set.of("//undo", "/undo");
    private static final List<String> TIER_COMPLETION_WORDS = List.of("slow", "mid", "fast", "auto");

    private boolean editSessionHookRegistered = false;
    private volatile boolean adapterRegistered = false;
    // -1 = 尚未跑過/反射結構性失敗；-2 = 官方 adapter 已存在，不需要我們的修補；
    // >=0 = 我們自己重建成功，值為實際帶有屬性的方塊型別數。
    private static final int PROPERTY_REGISTRY_SKIPPED_OFFICIAL_ADAPTER = -2;
    private volatile int propertyRegistryFixedCount = -1;
    private volatile boolean engineEnabled = false;
    // 診斷用暫時開關：僅供重現/排除原生崩潰場景時使用，正常使用不應開啟。
    private final boolean diagnosticAllowNativeUndo = System.getProperty("wepoc.allowNativeUndo") != null;
    // 每個 EditSessionEvent 觸發時新建一個 WEAsyncExtent，用併發表追蹤最近一個實例以便回報統計
    private final Map<UUID, WEAsyncExtent> lastExtentByActor = new ConcurrentHashMap<>();
    /**
     * 規格書第 4 節：從指令最後一個參數剝出來的檔位，暫存到這個 actor「下一次」觸發的
     * EditSessionEvent 用一次就丟（見 onCommandPreprocess/onEditSession）。沒有暫存值時
     * 回傳 mid，符合「未填寫檔位參數時，預設為 mid」。
     *
     * 2026-09-14 補防呆：正常情況下 put 跟 remove 都發生在同一次指令的同步派發過程裡
     * （PlayerCommandPreprocessEvent → WorldEdit 處理該指令 → EditSessionEvent，全部
     * 在同一個主執行緒呼叫堆疊內完成），理論上不會殘留。但如果玩家打的指令選區沒設好、
     * 語法錯誤、或任何原因讓 WorldEdit 根本沒建立 EditSession（例如指令在更前面就被
     * WorldEdit 自己擋下報錯），這個 put 進去的值就永遠不會被消費，會一直卡在 map 裡
     * ——單一筆不會怎樣，但長時間下來每個「打過帶檔位指令又失敗」的玩家都會留一筆
     * 用不到的殘留。加上時間戳記＋TTL，consume 時比對是否過期，並用一個低頻的
     * repeating task 定期清掉沒被消費、放太久的殘留項目。
     */
    private record PendingTier(SpeedTier tier, long insertedAtMillis) {
    }

    private static final long PENDING_TIER_TTL_MILLIS = 5000L;
    private final Map<UUID, PendingTier> pendingTierByActor = new ConcurrentHashMap<>();

    /**
     * 2026-09-15 委託人拿一個 5676 萬格的城堡 schematic 做壓力測試後提出的安全閘：任何預估
     * 會動到超過 {@link #DANGER_BLOCK_THRESHOLD} 格的操作（選區類指令看目前選區體積、
     * {@code //paste} 看 clipboard 體積），一律先擋下來，要求玩家先送出 {@code /wepoc danger
     * confirm} 才會放行——而且只放行「確認後的下一個 WorldEdit 指令」這一次，用一次就丟
     * （跟 {@link #pendingTierByActor} 同一種「用一次就丟＋TTL 防呆」模式）。委託人明確
     * 要求的語意：如果確認後玩家送出的下一個指令根本不是 WorldEdit 操作指令，這次確認直接
     * 失效，不能留著等下一次真的送操作指令時才用掉——見 {@link #onCommandPreprocess} 開頭
     * 那段判斷。
     *
     * 只用「選區/clipboard 的體積」而不是「實際會被改到的格數」來估計，是刻意的簡化：
     * 前者在指令真正執行前就能查到（`//replace` 之類的指令實際影響格數必須跑完才知道），
     * 用體積當保守上限（worst case）估計，足以擋下真正危險的大範圍操作，不需要先跑一次
     * 才知道要不要擋。
     */
    // 2026-09-15 用真實壓力測試校正過的數字：5676 萬格的城堡同步貼上，玩家看到「完成」
    // 訊息花了 81 秒（實際主執行緒卡住時間更長——WorldEdit 自己的原生 history 收尾還要
    // 再花時間，見驗證報告「二點十八」），已經超過 Paper 預設 60 秒看門狗時限。反推吞吐量
    // 約 70 萬格/秒，用預設看門狗時限反推大概 4200 萬格左右就會開始踩線，原本訂的 1 億格
    // 明顯太高——2000 萬格留了足夠安全邊際（且這次測試的城堡屬性複雜度不低，實際門檻
    // 可能因方塊種類更單純而更高，這裡刻意保守）。
    private static final long DANGER_BLOCK_THRESHOLD = 20_000_000L;
    private static final long DANGER_CONFIRM_TTL_MILLIS = 30_000L;
    private final Map<UUID, Long> pendingDangerConfirmByActor = new ConcurrentHashMap<>();
    private UndoManager undoManager;

    /**
     * 2026-09-15：第三方外掛（例如 BetterStructures 生成大型建築時）呼叫
     * {@code WorldEdit.getInstance().newEditSession(World)} 完全不帶 actor——反解確認過，
     * 這類呼叫觸發的 {@link EditSessionEvent#getActor()} 永遠是 {@code null}。原本這裡直接
     * {@code return}、整段編輯完全放給 WorldEdit 原生同步路徑處理，等於這類操作不管
     * {@code engine} 開關為何都攔不到——壓力測試已經證實這條原生路徑在大型操作下會讓主執行緒
     * 卡住超過看門狗時限（見驗證報告「二點十八」）。與其一個個去 patch 各個第三方外掛讓它們
     * 補上 actor（脆弱、且以後每次外掛更新都要重新確認），改成在這裡用一個固定的系統假
     * {@code UUID} 頂替，讓「沒有真人 actor 的編輯」也能走同一套 {@link WEAsyncExtent}/
     * {@link UndoManager} 分批限流管線。下游（{@link WEAsyncExtent}、{@link UndoManager}）
     * 全程只用 {@code UUID} 當 key，從未直接碰 WorldEdit 的 {@code Actor} 物件本身，且
     * {@code notifyPlayer}/{@code maybeSendProgressActionBar} 對「找不到對應線上玩家」
     * 已經是 null-safe（{@code Bukkit.getPlayer(actor) == null} 就靜默跳過，只留 log），
     * 所以這個系統假 actor 不需要下游額外配合就能安全共用整條管線——它甚至會累積自己的
     * undo 堆疊，理論上管理員之後可以用一個綁定同一個系統 UUID 的指令 undo 掉一次
     * 系統觸發的大型結構貼上（目前尚未實作對應指令，先留著這個可能性）。
     */
    static final UUID SYSTEM_ACTOR_UUID = new UUID(0L, 0L);

    @Override
    public void onEnable() {
        getCommand("wepoc").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        undoManager = new UndoManager(this);
        Bukkit.getScheduler().runTaskTimer(this, this::sweepStalePendingTiers, 100L, 100L);
        try {
            WorldEdit.getInstance().getEventBus().register(this);
            editSessionHookRegistered = true;
            getLogger().info("[WEPoC] EditSessionEvent 訂閱註冊成功");
        } catch (Throwable t) {
            getLogger().severe("[WEPoC] EditSessionEvent 訂閱註冊失敗: " + t);
            t.printStackTrace();
        }
        registerBukkitImplAdapter();
    }

    /**
     * 把 {@link WEBukkitAdapter} 塞進 WorldEdit 自己的 adapter 欄位——但只在「這個平台
     * 真的沒有官方 adapter」時才做。
     *
     * 2026-09-15 委託人的建築服環境（純 PaperMC，不是 Arclight）暴露了一個沒考慮到的情境：
     * 純 Paper 本身就在 WorldEdit 官方原生 adapter 清單裡，{@code WorldEditPlugin} 自己的
     * {@code loadAdapter()} 在啟動時就已經找到並注入了官方 adapter。這個方法原本無條件執行
     * 反射注入，會把官方已經裝好的 adapter **蓋掉**，換成我們這個功能刻意做得比較少的最小
     * 實作（例如 {@link WEBukkitAdapter} 完全不支援地形重生成、自訂生物群系等）——等於在
     * 一個原本就正常的環境上，把功能完整的官方實作換成一個閹割版，是純粹的倒退，不是修復。
     *
     * 修法：反射拿到 {@code adapter} 欄位（本來就需要，見下方）後，先問它「現在已經有
     * adapter 了嗎」——這一步不需要額外反射，直接把欄位值轉型成它實作的公開介面
     * {@code com.sk89q.worldedit.util.lifecycle.Lifecycled} 呼叫公開方法 {@code isValid()}
     * 即可（{@code WorldEditPlugin} 自己對外的 getter 是 package-private，叫不到，但介面
     * 本身是 public，轉型呼叫合法）。已經有效就代表這個平台（不管是純 Paper 還是任何未來
     * 版本 Arclight 補齊了官方支援）已經有可用的官方實作，直接跳過，讀取/寫入/屬性表全部
     * 交給官方 adapter 負責，不去動它。只有無效（目前只在 Arclight 這種缺官方 adapter 的
     * 環境會發生）才繼續執行下面這段注入。
     *
     * WorldEdit 在自己的 onEnable（比我們早，因為 plugin.yml 宣告 depend: [WorldEdit]）
     * 掃描 classpath 尋找官方 adapter 失敗後，會把內部的 {@code SimpleLifecycled<BukkitImplAdapter>}
     * 設成 invalid。那個欄位是 private，但型別本身（{@code SimpleLifecycled}）跟它的
     * {@code newValue(T)} 方法都是 public——用反射拿到欄位參照後，直接呼叫 public 方法即可，
     * 不需要碰任何 private 方法。
     *
     * 這個做法跟 WorldEdit 7.3.7 這個確切版本的內部欄位結構（欄位名稱 "adapter"、
     * {@code SimpleLifecycled} 這個型別、{@code newValue(Object)} 這個方法簽章）綁死——
     * WorldEdit 一旦升級改了這些內部細節，反射會直接失敗。失敗時不能安靜吞掉：這裡明確
     * 區分「反射打到的內部結構跟預期不符」（NoSuchFieldException/NoSuchMethodException/
     * ClassCastException，八成是 WorldEdit 版本變了）跟其他未預期例外，兩種都會在啟動時
     * 用最顯眼的方式報告「引擎的讀取類指令功能已停用」，不要讓管理員在指令莫名壞掉後
     * 還要重新從頭排查一輪才發現是這裡的問題。
     */
    private void registerBukkitImplAdapter() {
        com.sk89q.worldedit.bukkit.WorldEditPlugin worldEditPlugin =
                (com.sk89q.worldedit.bukkit.WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin == null) {
            getLogger().warning("[WEPoC] 找不到 WorldEdit 插件實例，無法註冊 BukkitImplAdapter");
            return;
        }
        try {
            java.lang.reflect.Field adapterField =
                    com.sk89q.worldedit.bukkit.WorldEditPlugin.class.getDeclaredField("adapter");
            adapterField.setAccessible(true);
            Object lifecycled = adapterField.get(worldEditPlugin);
            // WorldEditPlugin#getBukkitImplAdapter()/getLifecycledBukkitImplAdapter() 都是
            // package-private（反解確認 flags=0x0000），從我們自己的套件叫不到，但這個欄位
            // 實際型別 SimpleLifecycled 實作了公開介面 com.sk89q.worldedit.util.lifecycle.Lifecycled
            // ——轉型成這個公開介面型別後就能直接呼叫它的公開方法（isValid()），不需要碰任何
            // package-private 成員，也不用另外反射一個 getter。
            if (lifecycled instanceof com.sk89q.worldedit.util.lifecycle.Lifecycled<?> existing && existing.isValid()) {
                adapterRegistered = true;
                propertyRegistryFixedCount = PROPERTY_REGISTRY_SKIPPED_OFFICIAL_ADAPTER;
                getLogger().info("[WEPoC] 偵測到這個平台已經有官方 BukkitImplAdapter（例如純 Paper），"
                        + "不注入我們自己的最小實作，讀取/寫入/屬性表全部交給官方 adapter 負責");
                return;
            }
            java.lang.reflect.Method newValue = lifecycled.getClass().getMethod("newValue", Object.class);
            WEBukkitAdapter bukkitAdapter = new WEBukkitAdapter();
            newValue.invoke(lifecycled, bukkitAdapter);
            adapterRegistered = true;
            getLogger().info("[WEPoC] 已註冊 WEBukkitAdapter 作為 WorldEdit 的 BukkitImplAdapter"
                    + "（讓 //copy 等讀取類指令與 engine=off 時的原生寫入不再被 UnsupportedVersionEditException 擋住）");
            // WorldEdit 自己的 onEnable 在我們的 adapter 存在之前就已經跑過
            // initializeRegistries()，把每個 BlockType 的屬性表永久快取成空的
            // （見 PropertyRegistryFixer 開頭的說明），這裡補一次重建。
            propertyRegistryFixedCount = PropertyRegistryFixer.refreshAll(bukkitAdapter, getLogger());
        } catch (NoSuchFieldException | NoSuchMethodException | ClassCastException e) {
            adapterRegistered = false;
            getLogger().severe("====================================================================");
            getLogger().severe("[WEPoC] 【嚴重】WorldEdit 內部結構已變更，BukkitImplAdapter 反射注入失敗！");
            getLogger().severe("[WEPoC] 這代表 WorldEdit 版本可能已經更新，跟這個 PoC 寫死假設的");
            getLogger().severe("[WEPoC] WorldEditPlugin.adapter 欄位/SimpleLifecycled.newValue() 簽章不符。");
            getLogger().severe("[WEPoC] 影響範圍：//copy //smooth //deform 等讀取類指令、以及 engine=off");
            getLogger().severe("[WEPoC] 時的原生寫入路徑，將全部回到「Editing on unsupported versions is");
            getLogger().severe("[WEPoC] disabled」的不可用狀態。我們自己 engine=on 的寫入路徑不受影響。");
            getLogger().severe("[WEPoC] 請先確認目前安裝的 WorldEdit 版本，並重新比對 WorldEditPlugin 原始碼");
            getLogger().severe("[WEPoC] 的 adapter 欄位定義，再考慮升級這個 PoC 的相依版本或調整反射邏輯。");
            getLogger().severe("====================================================================");
            getLogger().severe("[WEPoC] 原始例外: " + e);
            e.printStackTrace();
        } catch (Throwable t) {
            adapterRegistered = false;
            getLogger().severe("[WEPoC] 註冊 BukkitImplAdapter 失敗（非預期例外），讀取類指令將維持原本的不可用狀態: " + t);
            t.printStackTrace();
        }
    }

    /**
     * 規格書第 4 節：檔位參數固定放在「所有支援本引擎的指令」的最後一個參數位置
     * （例：{@code //paste fast}、{@code //set stone mid}、{@code //undo auto}），
     * 未填時預設 mid。//undo //redo 額外一律攔截導到自建 UndoManager，完全不讓
     * WorldEdit 原生指令執行；其餘指令只做「剝掉尾端檔位關鍵字、記住給下一次
     * EditSessionEvent 用」，指令本身原封不動交給 WorldEdit 處理。
     *
     * 範圍刻意只鎖定「//」開頭的指令（WorldEdit 自己專用、幾乎不會跟其他外掛的單斜線
     * 指令撞名的命名空間）加上我們原本就攔截的 {@code /undo}/{@code /redo} 這兩個
     * WorldEdit 也會註冊的單斜線別名——不對任何其他單斜線指令做尾端剝字，避免誤觸到
     * 剛好最後一個參數叫 fast/mid/slow/auto 的其他外掛指令。
     *
     * diagnosticAllowNativeUndo 開啟時整段放行給原生路徑，僅供根因排除測試使用。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (diagnosticAllowNativeUndo) {
            return;
        }
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length == 0) {
            return;
        }
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        boolean isWorldEditNamespace = cmd.startsWith("//") || UNDO_REDO_COMMANDS.contains(cmd);
        Player player = event.getPlayer();

        if (!isWorldEditNamespace) {
            // 委託人明確要求的語意：danger confirm 只保護「確認後緊接著送出的下一個指令」，
            // 如果那個指令根本不是 WorldEdit 操作指令，確認就直接失效，不能留到之後真的送出
            // 操作指令時才被撿去用。
            pendingDangerConfirmByActor.remove(player.getUniqueId());
            return;
        }

        if (!UNDO_REDO_COMMANDS.contains(cmd)) {
            Long confirmedAtMillis = pendingDangerConfirmByActor.remove(player.getUniqueId());
            boolean dangerConfirmed = confirmedAtMillis != null
                    && System.currentTimeMillis() - confirmedAtMillis <= DANGER_CONFIRM_TTL_MILLIS;
            if (!dangerConfirmed) {
                long estimatedVolume = estimateOperationVolume(player, cmd);
                if (estimatedVolume > DANGER_BLOCK_THRESHOLD) {
                    event.setCancelled(true);
                    player.sendMessage(String.format(Locale.ROOT,
                            "[WEPoC] 這個操作預估會動到約 %,d 格，超過 %,d 格的危險門檻——有很大的風險"
                                    + "（可能長時間卡住主執行緒，甚至觸發看門狗強制關伺服器）。"
                                    + "確定要繼續的話，請先輸入 /wepoc danger confirm，緊接著（%d 秒內）"
                                    + "再送出這個指令一次。",
                            estimatedVolume, DANGER_BLOCK_THRESHOLD, DANGER_CONFIRM_TTL_MILLIS / 1000));
                    return;
                }
            }
        }

        SpeedTier tier = SpeedTier.MID;
        if (parts.length > 1 && isTierKeyword(parts[parts.length - 1])) {
            tier = SpeedTier.parse(parts[parts.length - 1]);
            parts = Arrays.copyOf(parts, parts.length - 1);
        }

        if (UNDO_REDO_COMMANDS.contains(cmd)) {
            event.setCancelled(true);
            ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
            UUID actorId = player.getUniqueId();
            SpeedTier finalTier = tier;
            // 2026-09-14 修掉一個實測抓到的排序 race：正向編輯的 enqueueForward 是在
            // onEditSession 裡透過 Bukkit.getScheduler().runTask(...) 延後一個 tick 才真正
            // push 進 UndoManager 的佇列（因為 EditSessionEvent 觸發當下，wrapped extent
            // 還沒收集完整個指令的變更）。undo/redo 原本是同步、當下立刻 push——如果玩家
            // 背靠背連續打「//set ... fast」緊接著「//undo slow」，undo 的 push 反而會搶在
            // 前一個 //set 的 push 之前，導致 //undo 復原到的是更早一筆編輯，不是玩家剛剛
            // 那筆 //set。這裡讓 undo/redo 也一樣延後一個 tick 再 push，兩種路徑用同一種
            // 排程時機，Bukkit 的 scheduler 對同一 tick 內排入、同樣 0-tick 延遲的工作會照
            // 排入順序執行，順序才會跟玩家實際下指令的順序一致。
            Bukkit.getScheduler().runTask(this, () -> {
                if (UNDO_COMMANDS.contains(cmd)) {
                    undoManager.requestUndo(actorId, level, finalTier);
                } else {
                    undoManager.requestRedo(actorId, level, finalTier);
                }
            });
            return;
        }

        // 一般編輯指令：把（可能已剝掉檔位關鍵字的）指令原樣交給 WorldEdit，
        // 檔位另外記著，等這個指令觸發的 EditSessionEvent 進來時才消耗掉一次。
        event.setMessage(String.join(" ", parts));
        pendingTierByActor.put(player.getUniqueId(), new PendingTier(tier, System.currentTimeMillis()));
    }

    /** 見 {@link #pendingTierByActor} 欄位註解：清掉沒被任何 EditSessionEvent 消費、放太久的殘留項目。 */
    private void sweepStalePendingTiers() {
        long now = System.currentTimeMillis();
        pendingTierByActor.entrySet().removeIf(e -> now - e.getValue().insertedAtMillis() > PENDING_TIER_TTL_MILLIS);
        // 見 pendingDangerConfirmByActor 欄位註解：正常情況下會在下一個指令就被消費/清掉，
        // 這裡只是保底，避免玩家確認完之後完全不再送任何指令，殘留一筆永遠用不到的確認。
        pendingDangerConfirmByActor.entrySet().removeIf(e -> now - e.getValue() > DANGER_CONFIRM_TTL_MILLIS);
    }

    private static boolean isTierKeyword(String token) {
        return TIER_COMPLETION_WORDS.contains(token.toLowerCase(Locale.ROOT));
    }

    /**
     * 危險操作防呆的體積估計：用「選區/clipboard 的體積」當保守上限，不是「實際會被改到的
     * 格數」（後者要跑完才知道）。{@code //paste} 看 clipboard 體積，其餘看目前選區體積。
     * 查不到（選區沒設好、clipboard 是空的、任何反解沒預期到的例外）就回傳 0——不擋，讓
     * WorldEdit 自己既有的錯誤訊息（"Make a region selection first." 等）去處理，不在這裡
     * 越權搶著報錯。
     */
    private long estimateOperationVolume(Player player, String cmd) {
        try {
            com.sk89q.worldedit.entity.Player weActor = BukkitAdapter.adapt(player);
            com.sk89q.worldedit.LocalSession session = WorldEdit.getInstance().getSessionManager().get(weActor);
            if (cmd.equals("//paste")) {
                return session.getClipboard().getClipboard().getRegion().getVolume();
            }
            com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
            return session.getSelection(world).getVolume();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 規格書第 4 節：檔位參數位置要支援 tab 補全，列出 fast/mid/slow/auto。
     * 用 {@link TabCompleteEvent} 而不是 {@code PluginCommand#setTabCompleter}——後者
     * 只能替自己宣告的指令註冊補全，WorldEdit 的 {@code //set}/{@code //paste} 等指令
     * 是它自己註冊的，我們只能用這個「攔截任何指令的補全請求」的事件去追加選項，沒辦法
     * 精準判斷玩家當下是不是「剛好在打最後一個參數」——所以這裡的做法是隻要偵測到是
     * 在幫一個 WorldEdit 指令補全（見 {@link #onCommandPreprocess} 同樣的命名空間判斷），
     * 就把還沒被目前輸入排除掉的檔位關鍵字一併加進候選清單，不強制要求一定是最後一格。
     */
    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getBuffer();
        String[] tokens = buffer.split("\\s+", -1);
        if (tokens.length < 2) {
            return;
        }
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        if (!(cmd.startsWith("//") || UNDO_REDO_COMMANDS.contains(cmd))) {
            return;
        }
        String currentToken = tokens[tokens.length - 1].toLowerCase(Locale.ROOT);
        List<String> merged = new ArrayList<>(event.getCompletions());
        for (String tierWord : TIER_COMPLETION_WORDS) {
            if (tierWord.startsWith(currentToken) && !merged.contains(tierWord)) {
                merged.add(tierWord);
            }
        }
        event.setCompletions(merged);
    }

    @Override
    public void onDisable() {
        try {
            WorldEdit.getInstance().getEventBus().unregister(this);
        } catch (Throwable ignored) {
        }
    }

    // BEFORE_HISTORY：換上只負責記錄變更前後狀態的 WEAsyncExtent，不在呼叫執行緒上落地寫入。
    // 指令執行緒同步跑完（累積好全部變更）後，下一個 tick 才把整包 EditRecord 交給
    // UndoManager 排隊，用限時分批流程落地——forward/undo/redo 三者共用同一套排程與寫入路徑。
    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        getLogger().info("[WEPoC] EditSessionEvent 觸發: actor=" + event.getActor()
                + " world=" + event.getWorld() + " stage=" + event.getStage());
        if (!engineEnabled) {
            return;
        }
        if (event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        try {
            org.bukkit.World bukkitWorld = BukkitAdapter.adapt(event.getWorld());
            ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            WEAsyncExtent wrapped = new WEAsyncExtent(event.getExtent(), level, this);
            event.setExtent(wrapped);
            // 見 SYSTEM_ACTOR_UUID 欄位註解：沒有真人 actor（第三方外掛的無 actor 呼叫）
            // 時頂替一個固定系統假 UUID，讓這類編輯一樣被接管、走分批限流，而不是放給
            // WorldEdit 原生同步路徑直接卡住主執行緒。
            UUID actorId = event.getActor() != null ? event.getActor().getUniqueId() : SYSTEM_ACTOR_UUID;
            lastExtentByActor.put(actorId, wrapped);
            String worldKey = level.dimension().location().toString();
            // 用一次就丟：這個 tier 只給「引發這次 EditSessionEvent 的那個指令」用，
            // 沒有暫存值（例如指令沒帶檔位參數）時預設 mid。remove 而非 get，同一筆
            // 不會被下一次 EditSessionEvent 重複吃到。
            PendingTier pending = pendingTierByActor.remove(actorId);
            SpeedTier tier = (pending != null && System.currentTimeMillis() - pending.insertedAtMillis() <= PENDING_TIER_TTL_MILLIS)
                    ? pending.tier()
                    : SpeedTier.MID;
            Bukkit.getScheduler().runTask(this, () -> {
                EditRecord record = new EditRecord(actorId, worldKey, wrapped.getChanges());
                undoManager.enqueueForward(actorId, level, record, tier);
            });
        } catch (Throwable t) {
            getLogger().severe("[WEPoC] 包裝 extent 失敗，本次編輯將維持原生路徑: " + t);
            t.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("用法: /wepoc <write|mspt|hookstatus|engine|stats|maxundo|danger|noactortest|rustlighttest|lightwritetest> [args]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "hookstatus":
                sender.sendMessage("[WEPoC] EditSessionEvent 已註冊: " + editSessionHookRegistered
                        + " BukkitImplAdapter 已註冊: " + adapterRegistered
                        + " property registry 重建: " + (propertyRegistryFixedCount == PROPERTY_REGISTRY_SKIPPED_OFFICIAL_ADAPTER
                                ? "不需要（官方 adapter 已存在）"
                                : propertyRegistryFixedCount < 0
                                        ? "失敗（見啟動 log，屬性語法/copy-paste 屬性保留已回歸遺失狀態）"
                                        : (propertyRegistryFixedCount + " 個型別帶屬性"))
                        + " engine=" + (engineEnabled ? "on" : "off"));
                return true;
            case "mspt":
                return handleMspt(sender);
            case "write":
                return handleWrite(sender, args);
            case "engine":
                return handleEngine(sender, args);
            case "stats":
                return handleStats(sender);
            case "maxundo":
                return handleMaxUndo(sender, args);
            case "verify":
                return handleVerify(sender, args);
            case "lightdiff":
                return handleLightDiff(sender, args);
            case "blockdiff":
                return handleBlockDiff(sender, args);
            case "propdump":
                return handlePropDump(sender, args);
            case "danger":
                return handleDangerConfirm(sender, args);
            case "noactortest":
                return handleNoActorTest(sender, args);
            case "rustlighttest":
                return handleRustLightTest(sender, args);
            case "lightwritetest":
                return handleLightWriteTest(sender, args);
            default:
                sender.sendMessage("用法: /wepoc <write|mspt|hookstatus|engine|stats|maxundo|verify|lightdiff|blockdiff|danger|noactortest|rustlighttest|lightwritetest> [args]");
                return true;
        }
    }

    // 比對兩個等大區域（A 與 A 平移 (ox,oy,oz) 後的 B）的光照值，分別回報「區域內部」與
    // 「區域外擴 buffer 圈（可能被波及但沒被直接寫入）」的相符/不相符數量，並列出前幾筆不符範例。
    // /wepoc lightdiff x1 y1 z1 x2 y2 z2 ox oy oz [buffer]
    private boolean handleLightDiff(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || args.length < 10) {
            sender.sendMessage("用法: /wepoc lightdiff x1 y1 z1 x2 y2 z2 ox oy oz [buffer=2]");
            return true;
        }
        try {
            int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
            int ox = Integer.parseInt(args[7]), oy = Integer.parseInt(args[8]), oz = Integer.parseInt(args[9]);
            int buffer = args.length >= 11 ? Integer.parseInt(args[10]) : 2;

            ServerLevel level = ((CraftWorld) ((Player) sender).getWorld()).getHandle();
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

            int interiorTotal = 0, interiorMismatch = 0, bufferTotal = 0, bufferMismatch = 0;
            List<String> interiorExamples = new ArrayList<>();
            List<String> bufferExamples = new ArrayList<>();

            for (int x = minX - buffer; x <= maxX + buffer; x++) {
                for (int y = minY - buffer; y <= maxY + buffer; y++) {
                    for (int z = minZ - buffer; z <= maxZ + buffer; z++) {
                        boolean interior = x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
                        BlockPos posA = new BlockPos(x, y, z);
                        BlockPos posB = new BlockPos(x + ox, y + oy, z + oz);
                        int skyA = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(posA);
                        int skyB = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(posB);
                        int blockA = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(posA);
                        int blockB = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(posB);
                        boolean mismatch = skyA != skyB || blockA != blockB;
                        if (interior) {
                            interiorTotal++;
                            if (mismatch) {
                                interiorMismatch++;
                                if (interiorExamples.size() < 5) {
                                    interiorExamples.add(String.format("內部 pos=(%d,%d,%d) sky=%d/%d block=%d/%d",
                                            x, y, z, skyA, skyB, blockA, blockB));
                                }
                            }
                        } else {
                            bufferTotal++;
                            if (mismatch) {
                                bufferMismatch++;
                                if (bufferExamples.size() < 5) {
                                    bufferExamples.add(String.format("緩衝圈 pos=(%d,%d,%d) sky=%d/%d block=%d/%d",
                                            x, y, z, skyA, skyB, blockA, blockB));
                                }
                            }
                        }
                    }
                }
            }

            sender.sendMessage(String.format("[WEPoC] lightdiff 內部=%d/%d不符 緩衝圈=%d/%d不符",
                    interiorMismatch, interiorTotal, bufferMismatch, bufferTotal));
            for (String ex : interiorExamples) {
                sender.sendMessage("[WEPoC]   " + ex);
            }
            for (String ex : bufferExamples) {
                sender.sendMessage("[WEPoC]   " + ex);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("座標必須是整數");
        }
        return true;
    }

    // 完整方塊狀態（含所有屬性，例如樓梯朝向/半層/形狀、告示牌等）逐格比對兩個等大區域，
    // 用來驗證 //copy //paste 這類「先讀後寫」的操作是否正確保留了方塊屬性，
    // 不是只驗證方塊種類（那是 /wepoc verify 的範圍）。
    // /wepoc blockdiff x1 y1 z1 x2 y2 z2 ox oy oz
    private boolean handleBlockDiff(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || args.length < 10) {
            sender.sendMessage("用法: /wepoc blockdiff x1 y1 z1 x2 y2 z2 ox oy oz");
            return true;
        }
        try {
            int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
            int ox = Integer.parseInt(args[7]), oy = Integer.parseInt(args[8]), oz = Integer.parseInt(args[9]);

            ServerLevel level = ((CraftWorld) ((Player) sender).getWorld()).getHandle();
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

            int total = 0, mismatch = 0;
            List<String> examples = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos posA = new BlockPos(x, y, z);
                        BlockPos posB = new BlockPos(x + ox, y + oy, z + oz);
                        String a = level.getBlockState(posA).toString();
                        String b = level.getBlockState(posB).toString();
                        total++;
                        if (!a.equals(b)) {
                            mismatch++;
                            if (examples.size() < 8) {
                                examples.add(String.format("pos=(%d,%d,%d) A=%s B=%s", x, y, z, a, b));
                            }
                        }
                    }
                }
            }
            sender.sendMessage(String.format("[WEPoC] blockdiff %d/%d不符（含完整屬性比對）", mismatch, total));
            for (String ex : examples) {
                sender.sendMessage("[WEPoC]   " + ex);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("座標必須是整數");
        }
        return true;
    }

    // 診斷用：直接印出 WorldEdit 自己的 BlockType 註冊表對某個方塊認得哪些屬性。
    // /wepoc propdump minecraft:oak_stairs
    private boolean handlePropDump(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /wepoc propdump <block id>");
            return true;
        }
        com.sk89q.worldedit.world.block.BlockType type = com.sk89q.worldedit.world.block.BlockTypes.get(args[1]);
        if (type == null) {
            sender.sendMessage("找不到方塊: " + args[1]);
            return true;
        }
        sender.sendMessage("[WEPoC] " + args[1] + " getPropertyMap keys=" + type.getPropertyMap().keySet());
        sender.sendMessage("[WEPoC] defaultState=" + type.getDefaultState());
        return true;
    }

    /**
     * 驗證 SYSTEM_ACTOR_UUID 那段修法：故意建立一個**完全不帶 actor** 的 EditSession
     * （跟 BetterStructures 的 {@code ModulePasting.java} 呼叫 {@code newEditSession(World)}
     * 是同一種呼叫方式），寫入一小塊區域，再檢查系統假 UUID 的 undo 深度有沒有增加——
     * 增加就代表這次編輯真的被我們的 {@link WEAsyncExtent}/{@link UndoManager} 接管，
     * 沒有走 WorldEdit 原生同步路徑（原生路徑完全不會碰 undoManager，深度永遠不動）。
     * /wepoc noactortest x1 y1 z1 x2 y2 z2
     */
    private boolean handleNoActorTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 7) {
            sender.sendMessage("用法: /wepoc noactortest x1 y1 z1 x2 y2 z2"
                    + "（模擬第三方外掛不帶 actor 呼叫 WorldEdit，驗證這類編輯會不會被引擎接管）");
            return true;
        }
        try {
            int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
            int depthBefore = undoManager.getUndoDepth(SYSTEM_ACTOR_UUID);
            com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(player.getWorld());
            try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(adaptedWorld)
                    .build()) {
                editSession.setBlocks(new com.sk89q.worldedit.regions.CuboidRegion(
                                com.sk89q.worldedit.math.BlockVector3.at(x1, y1, z1),
                                com.sk89q.worldedit.math.BlockVector3.at(x2, y2, z2)),
                        com.sk89q.worldedit.world.block.BlockTypes.STONE.getDefaultState());
            }
            // engine=off 時上面這段就是純原生同步路徑（跟沒裝這個引擎時 BetterStructures
            // 貼結構的行為完全一樣），系統假 UUID 從頭到尾不會被 UndoManager 碰到。
            // enqueueForward 是排到下一個 tick 才真正處理、寫入本身還要再經過分批限流跨數個
            // tick 完成，這裡不能立刻檢查深度，延遲 40 tick（2 秒，5x5x5 這種小範圍測試量綽綽
            // 有餘）再回報結果。
            sender.sendMessage("[WEPoC] noactortest 已送出（before 深度=" + depthBefore + "），2 秒後回報結果...");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int depthAfter = undoManager.getUndoDepth(SYSTEM_ACTOR_UUID);
                sender.sendMessage("[WEPoC] noactortest 結果：系統假 actor undo 深度 " + depthBefore
                        + " -> " + depthAfter + (depthAfter > depthBefore
                                ? "（被引擎接管，走分批限流管線）"
                                : "（engine=off 或攔截失敗，走 WorldEdit 原生同步路徑）"));
            }, 40L);
        } catch (NumberFormatException e) {
            sender.sendMessage("座標必須是整數");
        } catch (WorldEditException e) {
            sender.sendMessage("[WEPoC] noactortest 失敗: " + e);
        }
        return true;
    }

    /**
     * 光罩計算池（Rust）的獨立驗證指令，不碰 {@link UndoManager} 的正式 RELIGHT 路徑——
     * 只是把指定範圍（+外擴緩衝圈）的既有方塊資料讀出來、丟給 Rust 算一次，跟世界裡
     * 「已經是 vanilla 自己算出來的正確值」逐格比對，確認演算法本身站得住腳，再考慮接進
     * 正式路徑。驗收標準（委託人拍板）：不要求逐格完全一致，容許小幅落差（±1 算符合），
     * 只看整體有沒有大範圍離譜偏差。
     * /wepoc rustlighttest x1 y1 z1 x2 y2 z2
     */
    private boolean handleRustLightTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 7) {
            sender.sendMessage("用法: /wepoc rustlighttest x1 y1 z1 x2 y2 z2"
                    + "（拿範圍內既有的 vanilla 光值當 ground truth，驗證 Rust 光罩計算池算出來的值有多接近）");
            return true;
        }
        if (!RustLightEngine.isAvailable(getLogger())) {
            sender.sendMessage("[WEPoC] 光罩計算池 native 函式庫沒有成功載入，無法測試（見啟動 log）");
            return true;
        }
        try {
            int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
            int buffer = 2;
            int minX = Math.min(x1, x2) - buffer, maxX = Math.max(x1, x2) + buffer;
            int minY = Math.min(y1, y2) - buffer, maxY = Math.max(y1, y2) + buffer;
            int minZ = Math.min(z1, z2) - buffer, maxZ = Math.max(z1, z2) + buffer;
            int width = maxX - minX + 1, height = maxY - minY + 1, length = maxZ - minZ + 1;
            ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();

            byte[] opacity = new byte[width * height * length];
            byte[] blockSeed = new byte[width * height * length];
            byte[] skySeed = new byte[width * height * length];
            byte[] groundTruthBlock = new byte[width * height * length];
            byte[] groundTruthSky = new byte[width * height * length];
            var blockListener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
            var skyListener = level.getLightEngine().getLayerListener(LightLayer.SKY);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < length; z++) {
                        BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                        int idx = ((x * height) + y) * length + z;
                        BlockState state = level.getBlockState(pos);
                        opacity[idx] = (byte) state.getLightBlock(level, pos);
                        int actualBlockLight = blockListener.getLightValue(pos);
                        int actualSkyLight = skyListener.getLightValue(pos);
                        groundTruthBlock[idx] = (byte) actualBlockLight;
                        groundTruthSky[idx] = (byte) actualSkyLight;

                        boolean isBoundaryShell = x == 0 || y == 0 || z == 0
                                || x == width - 1 || y == height - 1 || z == length - 1;
                        int emission = state.getLightEmission();
                        if (isBoundaryShell) {
                            blockSeed[idx] = (byte) actualBlockLight;
                            skySeed[idx] = (byte) actualSkyLight;
                        } else if (emission > 0) {
                            blockSeed[idx] = (byte) emission;
                            skySeed[idx] = -1;
                        } else {
                            blockSeed[idx] = -1;
                            skySeed[idx] = -1;
                        }
                    }
                }
            }

            final int w = width, h = height, l = length;
            new Thread(() -> {
                var resultOpt = RustLightEngine.tryComputeLight(getLogger(), w, h, l, opacity, blockSeed, skySeed);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (resultOpt.isEmpty()) {
                        sender.sendMessage("[WEPoC] rustlighttest 失敗：光罩計算池呼叫失敗（見 log），fallback 情境本身也算驗證過了");
                        return;
                    }
                    RustLightEngine.Result result = resultOpt.get();
                    int total = 0, exactBlock = 0, closeBlock = 0, exactSky = 0, closeSky = 0;
                    int worstBlockDiff = 0, worstSkyDiff = 0;
                    for (int x = 1; x < w - 1; x++) {
                        for (int y = 1; y < h - 1; y++) {
                            for (int z = 1; z < l - 1; z++) {
                                int idx = ((x * h) + y) * l + z;
                                total++;
                                int diffBlock = Math.abs(result.blockLight()[idx] - groundTruthBlock[idx]);
                                int diffSky = Math.abs(result.skyLight()[idx] - groundTruthSky[idx]);
                                worstBlockDiff = Math.max(worstBlockDiff, diffBlock);
                                worstSkyDiff = Math.max(worstSkyDiff, diffSky);
                                if (diffBlock == 0) {
                                    exactBlock++;
                                }
                                if (diffBlock <= 1) {
                                    closeBlock++;
                                }
                                if (diffSky == 0) {
                                    exactSky++;
                                }
                                if (diffSky <= 1) {
                                    closeSky++;
                                }
                            }
                        }
                    }
                    sender.sendMessage(String.format(Locale.ROOT,
                            "[WEPoC] rustlighttest 內部 %d 格（不含外圈緩衝）：方塊光 完全相符=%d(%.1f%%) ±1內=%d(%.1f%%) 最大落差=%d"
                                    + " | 天空光 完全相符=%d(%.1f%%) ±1內=%d(%.1f%%) 最大落差=%d",
                            total, exactBlock, 100.0 * exactBlock / total, closeBlock, 100.0 * closeBlock / total, worstBlockDiff,
                            exactSky, 100.0 * exactSky / total, closeSky, 100.0 * closeSky / total, worstSkyDiff));
                });
            }, "wepoc-rustlighttest").start();
            sender.sendMessage("[WEPoC] rustlighttest 已送出到背景執行緒計算，範圍 " + width + "x" + height + "x" + length + "，稍後回報結果...");
        } catch (NumberFormatException e) {
            sender.sendMessage("座標必須是整數");
        }
        return true;
    }

    /**
     * 驗證「直接把光值寫回世界」這條路徑本身能不能用——跟 {@link #handleRustLightTest}
     * 驗證的是完全不同的事（那個只驗證演算法算出來的值準不準，這個不碰演算法，只驗證
     * {@code DataLayer}/{@code LevelLightEngine#queueSectionData} 這條寫入機制本身有沒有
     * 真的生效、query 得到）。故意先把值改壞、讀回確認真的變壞（證明寫入不是空氣），
     * 再用原本備份的 {@code DataLayer} 整份還原、讀回確認真的還原——全程不留任何實際
     * 破壞。/wepoc lightwritetest x y z
     */
    private boolean handleLightWriteTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 4) {
            sender.sendMessage("用法: /wepoc lightwritetest x y z（測試 DataLayer/queueSectionData 直接寫光值這條路徑本身能不能用，全程會還原、不留破壞）");
            return true;
        }
        try {
            int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
            BlockPos pos = new BlockPos(x, y, z);
            ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
            var lightEngine = level.getLightEngine();
            var blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
            net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(pos);

            int originalValue = blockListener.getLightValue(pos);
            net.minecraft.world.level.chunk.DataLayer existing = blockListener.getDataLayerData(sectionPos);
            net.minecraft.world.level.chunk.DataLayer backup = existing != null ? existing.copy()
                    : new net.minecraft.world.level.chunk.DataLayer();

            int localX = net.minecraft.core.SectionPos.sectionRelative(x);
            int localY = net.minecraft.core.SectionPos.sectionRelative(y);
            int localZ = net.minecraft.core.SectionPos.sectionRelative(z);
            int corruptedValue = (originalValue + 5) % 16;

            net.minecraft.world.level.chunk.DataLayer corrupted = backup.copy();
            corrupted.set(localX, localY, localZ, corruptedValue);
            // 2026-09-15 發現：這台 Paper 伺服器的 getLightEngine() 回傳的是 Paper 自己的
            // ThreadedLevelLightEngine——光照運算本來就已經跑在專屬背景執行緒，runLightUpdates()
            // 是它自己排程用的內部方法，外部手動呼叫會直接丟 UnsupportedOperationException
            // （"Ran automatically on a different thread!"）。改成單純 queueSectionData 排隊，
            // 交給它自己的排程機制非同步處理，延遲幾個 tick 後才讀回檢查有沒有生效。
            lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, corrupted);
            sender.sendMessage(String.format(Locale.ROOT,
                    "[WEPoC] lightwritetest: 原值=%d，已排隊改成=%d，1 秒後讀回確認+還原...",
                    originalValue, corruptedValue));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int afterCorrupt = blockListener.getLightValue(pos);
                boolean writeWorked = afterCorrupt == corruptedValue;
                sender.sendMessage(String.format(Locale.ROOT,
                        "[WEPoC] lightwritetest: 讀回=%d（寫入%s生效），現在排隊還原...",
                        afterCorrupt, writeWorked ? "有" : "沒有"));
                lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, backup);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    int afterRestore = blockListener.getLightValue(pos);
                    boolean restoreWorked = afterRestore == originalValue;
                    sender.sendMessage(String.format(Locale.ROOT,
                            "[WEPoC] lightwritetest: 還原後讀回=%d（還原%s成功）",
                            afterRestore, restoreWorked ? "" : "沒有"));
                    if (!writeWorked) {
                        sender.sendMessage("[WEPoC] !!! queueSectionData 這條路徑目前看起來沒有真的生效，不能拿來取代 checkBlock !!!");
                    } else if (!restoreWorked) {
                        sender.sendMessage("[WEPoC] !!! 還原失敗，這個位置的光值可能被改壞了，建議手動 /wepoc write 或重載該 chunk !!!");
                    }
                }, 20L);
            }, 20L);
        } catch (NumberFormatException e) {
            sender.sendMessage("座標必須是整數");
        }
        return true;
    }

    // 見 pendingDangerConfirmByActor 欄位註解。/wepoc danger confirm
    private boolean handleDangerConfirm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("需在遊戲內執行");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage("用法: /wepoc danger confirm（用來放行上一個因為預估影響格數"
                    + "超過 " + String.format(Locale.ROOT, "%,d", DANGER_BLOCK_THRESHOLD) + " 格被擋下的操作，"
                    + "只對「緊接著送出的下一個指令」有效，用一次就丟）");
            return true;
        }
        pendingDangerConfirmByActor.put(player.getUniqueId(), System.currentTimeMillis());
        sender.sendMessage(String.format(Locale.ROOT,
                "[WEPoC] 危險操作已確認，%d 秒內送出的下一個 WorldEdit 指令會被放行一次"
                        + "（不管那個指令實際大小），送出其他任何指令都會讓這次確認失效。",
                DANGER_CONFIRM_TTL_MILLIS / 1000));
        return true;
    }

    // 權威性讀取真實世界方塊資料做比對，不只看聊天室回饋訊息。
    // /wepoc verify x1 y1 z1 x2 y2 z2 <stone|air>
    private boolean handleVerify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || args.length < 8) {
            sender.sendMessage("用法: /wepoc verify x1 y1 z1 x2 y2 z2 <stone|air>");
            return true;
        }
        Player player = (Player) sender;
        try {
            int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
            int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
            String expected = args[7].toLowerCase(Locale.ROOT);
            BlockState expectedState = expected.equals("air") ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
            ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            int total = 0, matched = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        total++;
                        if (level.getBlockState(pos).is(expectedState.getBlock())) {
                            matched++;
                        }
                    }
                }
            }
            sender.sendMessage("[WEPoC] verify " + expected + ": " + matched + "/" + total
                    + (matched == total ? " 全部相符" : " 有不相符！"));
        } catch (NumberFormatException e) {
            sender.sendMessage("座標必須是整數");
        }
        return true;
    }

    private boolean handleMaxUndo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /wepoc maxundo <n>，目前=" + undoManager.getMaxStackPerPlayer()
                    + "（PoC 階段的執行期設定，正式版應改接 config.yml）");
            return true;
        }
        try {
            undoManager.setMaxStackPerPlayer(Integer.parseInt(args[1]));
            sender.sendMessage("[WEPoC] 每位玩家最大 undo/redo 深度設為 " + undoManager.getMaxStackPerPlayer());
        } catch (NumberFormatException e) {
            sender.sendMessage("請輸入整數");
        }
        return true;
    }

    private boolean handleEngine(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /wepoc engine <on|off>，目前=" + (engineEnabled ? "on" : "off"));
            return true;
        }
        engineEnabled = args[1].equalsIgnoreCase("on");
        sender.sendMessage("[WEPoC] engine 設為 " + (engineEnabled ? "on" : "off"));
        getLogger().info("[WEPoC] engine 設為 " + (engineEnabled ? "on" : "off"));
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("需在遊戲內執行");
            return true;
        }
        UUID actor = ((Player) sender).getUniqueId();
        WEAsyncExtent extent = lastExtentByActor.get(actor);
        String extentPart = extent == null
                ? "尚無此玩家的 extent 紀錄"
                : ("最近一次 extent 記錄=" + extent.getRecordedCount() + " fallback=" + extent.getFallbackCount());
        // 檔位現在是每個指令自己帶的（規格書第 4 節：最後一個參數），沒有單一「目前檔位」
        // 這種全域概念可以顯示——auto 的動態預算倒是全域共用同一個控制迴圈，仍值得show 出來。
        String autoPart = String.format("auto 目前動態預算=%.2fms", undoManager.getAutoBudgetNanos() / 1_000_000.0);
        sender.sendMessage("[WEPoC] " + extentPart
                + " | " + autoPart
                + " | undo 深度=" + undoManager.getUndoDepth(actor)
                + " redo 深度=" + undoManager.getRedoDepth(actor));
        return true;
    }

    private boolean handleMspt(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("需在遊戲內執行");
            return true;
        }
        Player player = (Player) sender;
        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        MinecraftServer server = level.getServer();
        try {
            long avgNanos = server.getAverageTickTimeNanos();
            long[] recent = server.getTickTimesNanos();
            long lastNanos = recent.length > 0 ? recent[(server.getTickCount() - 1 + recent.length) % recent.length] : -1;
            sender.sendMessage(String.format(
                    "[WEPoC] MSPT 平均=%.3fms 最近一筆=%.3fms tickCount=%d",
                    avgNanos / 1_000_000.0, lastNanos / 1_000_000.0, server.getTickCount()));
            getLogger().info("[WEPoC] MSPT 平均=" + (avgNanos / 1_000_000.0) + "ms");
        } catch (Throwable t) {
            sender.sendMessage("[WEPoC] 讀取 MSPT 介面失敗: " + t);
            getLogger().severe("[WEPoC] MSPT 讀取失敗: " + t);
            t.printStackTrace();
        }
        return true;
    }

    private boolean handleWrite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("需在遊戲內執行");
            return true;
        }
        Player player = (Player) sender;
        int count = 100;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
            }
        }

        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        Location loc = player.getLocation();
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();

        List<BlockPos> touched = new ArrayList<>(count);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        long t0 = System.nanoTime();
        boolean crashed = false;
        Throwable crashCause = null;
        try {
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(baseX + i, baseY, baseZ);
                LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
                int sectionIndex = chunk.getSectionIndex(pos.getY());
                if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                    continue;
                }
                LevelChunkSection section = chunk.getSection(sectionIndex);
                BlockState target = (i % 2 == 0) ? stone : air;
                // 直接寫 section palette，跳過 Level.setBlock 整條管線（無鄰居通知/無紅石/無自動觸發光照）
                section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, target, false);
                touched.add(pos);
            }
        } catch (Throwable t) {
            crashed = true;
            crashCause = t;
        }
        long t1 = System.nanoTime();

        long relightNanos = -1;
        long broadcastNanos = -1;
        if (!crashed) {
            long r0 = System.nanoTime();
            try {
                for (BlockPos pos : touched) {
                    level.getLightEngine().checkBlock(pos);
                }
            } catch (Throwable t) {
                crashed = true;
                crashCause = t;
            }
            relightNanos = System.nanoTime() - r0;

            if (!crashed) {
                long b0 = System.nanoTime();
                try {
                    for (BlockPos pos : touched) {
                        BlockState now = level.getBlockState(pos);
                        level.sendBlockUpdated(pos, now, now, 3);
                    }
                } catch (Throwable t) {
                    crashed = true;
                    crashCause = t;
                }
                broadcastNanos = System.nanoTime() - b0;
            }
        }

        if (crashed) {
            sender.sendMessage("[WEPoC] 直寫測試在第 " + touched.size() + " 格後拋出例外: " + crashCause);
            getLogger().severe("[WEPoC] 直寫測試失敗");
            crashCause.printStackTrace();
        } else {
            sender.sendMessage(String.format(
                    "[WEPoC] 直寫 %d 格完成，寫入=%.2fms relight=%.2fms 廣播=%.2fms（無崩潰）",
                    touched.size(), (t1 - t0) / 1_000_000.0, relightNanos / 1_000_000.0, broadcastNanos / 1_000_000.0));
            getLogger().info("[WEPoC] 直寫測試通過, count=" + touched.size());
        }
        return true;
    }
}
