package ml.mypals.litematicafastmove.mesh;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;


public class LayerRangeClip {
    record Range(Direction.Axis axis, int min, int max) {}
    static Range snapshot() {
        LayerRange range = DataManager.getRenderLayerRange();
        if (range == null || range.getLayerMode() == LayerMode.ALL) return null;
        return new Range(range.getAxis(), range.getLayerMin(), range.getLayerMax());
    }

    static PlacementMesh.Clip compute(Range range, BlockPos min, BlockPos max, BlockPos sectionOrigin, BlockPos delta) {
        if (range == null) return null;

        Direction.Axis axis = range.axis();
        int origin = axis.choose(sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ());
        int d = axis.choose(delta.getX(), delta.getY(), delta.getZ());
        int secMin = Math.max(origin, axis.choose(min.getX(), min.getY(), min.getZ()));
        int secMax = Math.min(origin + 15, axis.choose(max.getX(), max.getY(), max.getZ()));
        int lo = Math.max(secMin + d, range.min());
        int hi = Math.min(secMax + d, range.max());

        if (lo <= secMin + d && hi >= secMax + d) return null;
        if (lo > hi) return new PlacementMesh.Clip(axis, 1, 0);
        return new PlacementMesh.Clip(axis, lo - d - origin, hi - d - origin);
    }
}
