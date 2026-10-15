package ml.mypals.litematicafastmove.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import ml.mypals.litematicafastmove.mesh.MeshSectionBuilder;
import ml.mypals.litematicafastmove.verify.AsyncSchematicVerifier;
import net.minecraft.core.BlockPos;

/**
 * 配置项注入 Litematica：保存在 litematica.json 的 "FastMove" 分类下，显示在 Litematica 配置界面的 Generic 页
 * (见 MixinConfigs / MixinGuiConfigs)。名称与说明见 assets/litematicafastmove/lang
 */
public class FastMoveConfig {
    public static final String CATEGORY = "FastMove";
    private static final String KEY = "litematicafastmove.config";

    public static final ConfigBoolean ENABLE_FAST_MOVE = new ConfigBoolean("fastMoveEnabled", true, "").apply(KEY);
    public static final ConfigBoolean TAKEOVER_ALL_PLACEMENTS = new ConfigBoolean("fastMoveTakeoverAllPlacements", true, "").apply(KEY);
    public static final ConfigInteger DETACHED_VOLUME_THRESHOLD = new ConfigInteger("fastMoveLargePlacementVolume", 32 * 32 * 32, 1, Integer.MAX_VALUE, "").apply(KEY);
    public static final ConfigBoolean ENABLE_ASYNC_VERIFY = new ConfigBoolean("fastMoveAsyncVerifier", true, "").apply(KEY);
    public static final ConfigInteger COMMIT_DELAY_MS = new ConfigInteger("fastMoveCommitDelay", 1500, 0, 60_000, "").apply(KEY);
    public static final ConfigDouble MOVE_SMOOTHING_MS = new ConfigDouble("fastMoveSmoothing", 60, 0, 1000, "").apply(KEY);
    public static final ConfigDouble MOVE_SNAP_DISTANCE = new ConfigDouble("fastMoveSnapDistance", 128, 0, 30_000_000, "").apply(KEY);

    // 每帧预算 (渲染线程)
    public static final ConfigInteger UPLOAD_KB_PER_FRAME = new ConfigInteger("fastMoveUploadKbPerFrame", 8 * 1024, 64, 1024 * 1024, "").apply(KEY);
    public static final ConfigDouble UPLOAD_MILLIS_PER_FRAME = new ConfigDouble("fastMoveUploadMsPerFrame", 3.0, 0.1, 100, "").apply(KEY);
    public static final ConfigInteger TRANSLUCENT_RESORTS_PER_FRAME = new ConfigInteger("fastMoveResortsPerFrame", 32, 0, 4096, "").apply(KEY);
    public static final ConfigDouble TRANSLUCENT_RESORT_DISTANCE = new ConfigDouble("fastMoveResortDistance", 1.0, 0, 64, "").apply(KEY);

    // 每 tick 预算 (主线程)
    public static final ConfigInteger VERIFIER_MERGE_MILLIS_PER_TICK = new ConfigInteger("fastMoveVerifierMergeMsPerTick", 50, 1, 1000, "").apply(KEY);

    // 线程数 (0 = 自动)
    public static final ConfigInteger MESH_BUILDER_THREADS = new ConfigInteger("fastMoveMeshBuilderThreads", 0, 0, 64, "").apply(KEY);
    public static final ConfigInteger VERIFIER_THREADS = new ConfigInteger("fastMoveVerifierThreads", 0, 0, 64, "").apply(KEY);

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
            ENABLE_FAST_MOVE,
            TAKEOVER_ALL_PLACEMENTS,
            DETACHED_VOLUME_THRESHOLD,
            ENABLE_ASYNC_VERIFY,
            COMMIT_DELAY_MS,
            MOVE_SMOOTHING_MS,
            MOVE_SNAP_DISTANCE,
            UPLOAD_KB_PER_FRAME,
            UPLOAD_MILLIS_PER_FRAME,
            TRANSLUCENT_RESORTS_PER_FRAME,
            TRANSLUCENT_RESORT_DISTANCE,
            VERIFIER_MERGE_MILLIS_PER_TICK,
            MESH_BUILDER_THREADS,
            VERIFIER_THREADS
    );

    static {
        // 接管范围变化：Litematica 的区块网格要重新包含 / 排除这些投影，独立网格随之重建
        ENABLE_FAST_MOVE.setValueChangeCallback(config -> SchematicWorldRefresher.INSTANCE.updateAll());
        TAKEOVER_ALL_PLACEMENTS.setValueChangeCallback(config -> SchematicWorldRefresher.INSTANCE.updateAll());
        DETACHED_VOLUME_THRESHOLD.setValueChangeCallback(config -> SchematicWorldRefresher.INSTANCE.updateAll());
        // 线程池运行时调整大小
        MESH_BUILDER_THREADS.setValueChangeCallback(config -> MeshSectionBuilder.applyThreadCount());
        VERIFIER_THREADS.setValueChangeCallback(config -> AsyncSchematicVerifier.applyThreadCount());
    }

    public static boolean isFastMoveEnabled() {
        return ENABLE_FAST_MOVE.getBooleanValue();
    }

    public static long uploadBytesPerFrame() {
        return UPLOAD_KB_PER_FRAME.getIntegerValue() * 1024L;
    }

    public static int meshBuilderThreads() {
        int value = MESH_BUILDER_THREADS.getIntegerValue();
        return value > 0 ? value : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    public static int verifierThreads() {
        int value = VERIFIER_THREADS.getIntegerValue();
        return value > 0 ? value : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    public static boolean isRenderTakenOver(SchematicPlacement placement) {
        if (!isFastMoveEnabled() || placement == null) return false;
        long volume = getVolume(placement);
        return volume > 0 && (TAKEOVER_ALL_PLACEMENTS.getBooleanValue() || volume >= DETACHED_VOLUME_THRESHOLD.getIntegerValue());
    }

    public static boolean shouldDeferCommit(SchematicPlacement placement) {
        if (!isFastMoveEnabled() || placement == null) return false;
        return getVolume(placement) >= DETACHED_VOLUME_THRESHOLD.getIntegerValue();
    }

    private static long getVolume(SchematicPlacement placement) {
        long totalVolume = 0;
        ImmutableMap<String, Box> map = placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED);
        for (Box b : map.values()) {
            BlockPos p1 = b.getPos1();
            BlockPos p2 = b.getPos2();
            if (p1 == null || p2 == null) continue;
            long dx = Math.abs(p1.getX() - p2.getX()) + 1L;
            long dy = Math.abs(p1.getY() - p2.getY()) + 1L;
            long dz = Math.abs(p1.getZ() - p2.getZ()) + 1L;
            totalVolume += dx * dy * dz;
        }
        return totalVolume;
    }
}
