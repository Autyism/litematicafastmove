package ml.mypals.litematicafastmove.config;

import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.core.BlockPos;

public class FastMoveConfig {
    public static boolean ENABLE_FAST_MOVE = true;
    public static boolean TAKEOVER_ALL_PLACEMENTS = true;
    public static int DETACHED_VOLUME_THRESHOLD = 32*32*32;
    public static boolean ENABLE_ASYNC_VERIFY = true;
    public static long COMMIT_DELAY_MS = 1500;
    public static double MOVE_SMOOTHING_MS = 60;
    // 单次位移超过此距离 (格) 时不插值，直接瞬移
    public static double MOVE_SNAP_DISTANCE = 128.0;

    // 每帧预算 (渲染线程)
    // 网格上传 (创建 GPU buffer)：字节数与耗时，任一用尽即停，剩余留到下一帧
    public static long UPLOAD_BYTES_PER_FRAME = 8L * 1024 * 1024;
    public static double UPLOAD_MILLIS_PER_FRAME = 3.0;
    // 半透明区段重新排序：每帧最多提交的数量，及触发重新排序的相机移动距离 (格)
    public static int TRANSLUCENT_RESORTS_PER_FRAME = 32;
    public static double TRANSLUCENT_RESORT_DISTANCE = 1.0;

    // 每 tick 预算 (主线程)
    // 校验结果合并：从 tick 开始算起的时间上限 (毫秒)
    public static long VERIFIER_MERGE_MILLIS_PER_TICK = 50;

    // 线程数 (启动时读取，修改后需重启)
    public static int MESH_BUILDER_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    public static int VERIFIER_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    public static boolean isRenderTakenOver(SchematicPlacement placement) {
        if (!ENABLE_FAST_MOVE || placement == null) return false;
        long volume = getVolume(placement);
        return volume > 0 && (TAKEOVER_ALL_PLACEMENTS || volume >= DETACHED_VOLUME_THRESHOLD);
    }

    public static boolean shouldDeferCommit(SchematicPlacement placement) {
        if (!ENABLE_FAST_MOVE || placement == null) return false;
        return getVolume(placement) >= DETACHED_VOLUME_THRESHOLD;
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
