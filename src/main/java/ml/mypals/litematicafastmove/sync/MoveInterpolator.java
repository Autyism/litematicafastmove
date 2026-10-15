package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MoveInterpolator {
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

            if (alpha >= 1.0 || dx * dx + dy * dy + dz * dz > FastMoveConfig.MOVE_SNAP_DISTANCE * FastMoveConfig.MOVE_SNAP_DISTANCE) {
                displayed[0] = target.getX();
                displayed[1] = target.getY();
                displayed[2] = target.getZ();
                return;
            }

            displayed[0] = Math.abs(dx) < EPSILON ? target.getX() : displayed[0] + dx * alpha;
            displayed[1] = Math.abs(dy) < EPSILON ? target.getY() : displayed[1] + dy * alpha;
            displayed[2] = Math.abs(dz) < EPSILON ? target.getZ() : displayed[2] + dz * alpha;
        });

        List<LagBox> lagging = new ArrayList<>();
        DISPLAYED.forEach((placement, displayed) -> {
            BlockPos target = placement.getOrigin();
            Vec3 lag = new Vec3(displayed[0] - target.getX(), displayed[1] - target.getY(), displayed[2] - target.getZ());
            if (lag.lengthSqr() < EPSILON * EPSILON) return;

            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                BlockPos a = box.getPos1(), b = box.getPos2();
                if (a == null || b == null) continue;
                lagging.add(new LagBox(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()), lag));
            }
        });
        laggingBoxes = lagging.isEmpty() ? List.of() : lagging;
    }

    private record LagBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Vec3 lag) {}

    private static volatile List<LagBox> laggingBoxes = List.of();

    public static boolean hasLagging() {
        return !laggingBoxes.isEmpty();
    }

    public static Vec3 getLagShiftAt(double x, double y, double z) {
        List<LagBox> boxes = laggingBoxes;
        if (boxes.isEmpty()) return null;

        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (LagBox box : boxes) {
            if (bx >= box.minX() && bx <= box.maxX() && by >= box.minY() && by <= box.maxY() && bz >= box.minZ() && bz <= box.maxZ()) {
                return box.lag();
            }
        }
        return null;
    }

    public static Vec3 getLag(SchematicPlacement placement) {
        BlockPos origin = placement.getOrigin();
        double[] displayed = DISPLAYED.computeIfAbsent(placement, point -> new double[]{origin.getX(), origin.getY(), origin.getZ()});
        return new Vec3(displayed[0] - origin.getX(), displayed[1] - origin.getY(), displayed[2] - origin.getZ());
    }

    public static void forget(SchematicPlacement placement) {
        DISPLAYED.remove(placement);
    }

    public static void clear() {
        DISPLAYED.clear();
        laggingBoxes = List.of();
    }
}
