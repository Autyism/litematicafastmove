package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MoveInterpolator {
    private static final double SNAP_DISTANCE = 128.0;
    private static final double EPSILON = 1.0E-3;

    private static final Map<SchematicPlacement, double[]> DISPLAYED
            = new ConcurrentHashMap<>();
    private static long lastFrameNanos;

    public static void update() {
        long now = System.nanoTime();
        double dtMs = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1.0E6;
        lastFrameNanos = now;

        double tau = FastMoveConfig.MOVE_SMOOTHING_MS;
        double alpha = tau <= 0 ? 1.0 : 1.0 - Math.exp(-Math.min(dtMs, 250.0) / tau);

        DISPLAYED.forEach((placement, displayed) -> {
            BlockPos target = placement.getOrigin();
            double dx = target.getX() - displayed[0];
            double dy = target.getY() - displayed[1];
            double dz = target.getZ() - displayed[2];

            if (alpha >= 1.0 || dx * dx + dy * dy + dz * dz > SNAP_DISTANCE * SNAP_DISTANCE) {
                displayed[0] = target.getX();
                displayed[1] = target.getY();
                displayed[2] = target.getZ();
                return;
            }

            displayed[0] = Math.abs(dx) < EPSILON ? target.getX() : displayed[0] + dx * alpha;
            displayed[1] = Math.abs(dy) < EPSILON ? target.getY() : displayed[1] + dy * alpha;
            displayed[2] = Math.abs(dz) < EPSILON ? target.getZ() : displayed[2] + dz * alpha;
        });
    }

    /**
     * 显示原点相对真实原点的滞后量 (显示 - 真实)；首次查询的投影没有滞后
     */
    public static Vec3 getLag(SchematicPlacement placement) {
        BlockPos origin = placement.getOrigin();
        double[] displayed = DISPLAYED.computeIfAbsent(placement, p -> new double[]{origin.getX(), origin.getY(), origin.getZ()});
        return new Vec3(displayed[0] - origin.getX(), displayed[1] - origin.getY(), displayed[2] - origin.getZ());
    }

    public static void forget(SchematicPlacement placement) {
        DISPLAYED.remove(placement);
    }

    public static void clear() {
        DISPLAYED.clear();
    }
}
