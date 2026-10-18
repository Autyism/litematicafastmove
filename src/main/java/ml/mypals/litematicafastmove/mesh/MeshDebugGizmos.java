package ml.mypals.litematicafastmove.mesh;

import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class MeshDebugGizmos {
    private static final int QUEUED = 0xFFFFB000;
    private static final int APPLIED = 0xFF40FF80;
    private static final int SKIPPED = 0xFF808080;
    private static final int FULL = 0xFF40A0FF;
    private static final int MAX_PER_FRAME = 512;

    private record Box(AABB aabb, int color) {}

    private static final Queue<Box> PENDING = new ConcurrentLinkedQueue<>();

    private MeshDebugGizmos() {}

    public static boolean isEnabled() {
        return FastMoveConfig.DEBUG_GIZMOS.getBooleanValue();
    }

    static void fullBuild(PlacementBlockAccessor accessor, BlockPos delta, boolean applied) {
        if (!isEnabled() || accessor == null) return;
        PENDING.add(new Box(new AABB(
                accessor.minPos.getX() + delta.getX(), accessor.minPos.getY() + delta.getY(), accessor.minPos.getZ() + delta.getZ(),
                accessor.maxPos.getX() + delta.getX() + 1, accessor.maxPos.getY() + delta.getY() + 1, accessor.maxPos.getZ() + delta.getZ() + 1),
                applied ? APPLIED : FULL));
    }

    static void sectionsQueued(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta) {
        addSections(accessor, keys, delta, QUEUED);
    }

    static void sectionsApplied(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta) {
        addSections(accessor, keys, delta, APPLIED);
    }

    static void sectionsSkipped(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta) {
        addSections(accessor, keys, delta, SKIPPED);
    }

    private static void addSections(PlacementBlockAccessor accessor, Collection<Long> keys, BlockPos delta, int color) {
        if (!isEnabled() || accessor == null || keys == null) return;

        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        for (long key : keys) {
            int sx = SectionPos.x(key) << 4, sy = SectionPos.y(key) << 4, sz = SectionPos.z(key) << 4;
            int x0 = Math.max(sx, min.getX()), x1 = Math.min(sx + 15, max.getX());
            int y0 = Math.max(sy, min.getY()), y1 = Math.min(sy + 15, max.getY());
            int z0 = Math.max(sz, min.getZ()), z1 = Math.min(sz + 15, max.getZ());
            if (x0 > x1 || y0 > y1 || z0 > z1) continue;

            PENDING.add(new Box(new AABB(x0 + delta.getX(), y0 + delta.getY(), z0 + delta.getZ(),
                    x1 + delta.getX() + 1, y1 + delta.getY() + 1, z1 + delta.getZ() + 1), color));
        }
    }

    public static void flush() {
        if (PENDING.isEmpty()) return;
        if (!isEnabled()) {
            PENDING.clear();
            return;
        }

        SchematicExit.portal(() -> {
            int millis = FastMoveConfig.DEBUG_GIZMO_MILLIS.getIntegerValue();
            Box box;
            int count = 0;
            while (count++ < MAX_PER_FRAME && (box = PENDING.poll()) != null) {
                int fill = (box.color() & 0x00FFFFFF) | 0x20000000;
                Gizmos.cuboid(box.aabb(), GizmoStyle.strokeAndFill(box.color(), 2.0f, fill))
                      .persistForMillis(millis)
                      .fadeOut();
            }
        });
    }
}
