package ml.mypals.litematicafastmove.access;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlacementAccessorRegistry {
    private static final Map<SchematicPlacement, PlacementBlockAccessor> ACCESSOR_CACHE = new ConcurrentHashMap<>();

    public static PlacementBlockAccessor getOrCreate(SchematicPlacement placement) {
        if (placement == null) return null;
        return ACCESSOR_CACHE.compute(placement, (k, old) -> {
            if (old == null || !old.origin.equals(placement.getOrigin())) {
                return new PlacementBlockAccessor(placement);
            }
            return old;
        });
    }

    public static BlockState getBlockStateAt(int worldX, int worldY, int worldZ) {
        var manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return null;

        SchematicPlacement selected = manager.getSelectedSchematicPlacement();
        if (selected != null && selected.isEnabled()) {
            PlacementBlockAccessor accessor = getOrCreate(selected);
            if (accessor != null && accessor.isInBoundingBox(worldX, worldY, worldZ)) {
                BlockState state = accessor.getBlockStateAt(worldX, worldY, worldZ);
                if (!state.isAir()) {
                    return state;
                }
            }
        }

        var all = manager.getAllSchematicsPlacements();
        if (all != null) {
            for (SchematicPlacement placement : all) {
                if (placement != selected && placement != null && placement.isEnabled()) {
                    PlacementBlockAccessor accessor = getOrCreate(placement);
                    if (accessor != null && accessor.isInBoundingBox(worldX, worldY, worldZ)) {
                        BlockState state = accessor.getBlockStateAt(worldX, worldY, worldZ);
                        if (!state.isAir()) {
                            return state;
                        }
                    }
                }
            }
        }

        return null;
    }

    public static void invalidate(SchematicPlacement placement) {
        if (placement != null) {
            ACCESSOR_CACHE.remove(placement);
        }
    }

    public static void clear() {
        ACCESSOR_CACHE.clear();
    }
}
