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
