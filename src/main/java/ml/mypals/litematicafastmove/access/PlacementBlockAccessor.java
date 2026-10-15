package ml.mypals.litematicafastmove.access;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class PlacementBlockAccessor {
    public final SchematicPlacement placement;
    public final LitematicaSchematic schematic;
    public final BlockPos origin;
    public final BlockPos minPos;
    public final BlockPos maxPos;
    private final List<RegionEntry> regions = new ArrayList<>();

    private record RegionEntry(RegionTransform transform, LitematicaBlockStateContainer container) {}

    public PlacementBlockAccessor(SchematicPlacement placement) {
        this.placement = placement;
        this.schematic = placement.getSchematic();
        this.origin = placement.getOrigin();

        var boxes = placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED);
        BlockPos p1 = null;
        BlockPos p2 = null;
        for (var b : boxes.values()) {
            assert b.getPos1() != null;
            assert b.getPos2() != null;
            BlockPos boxMin = PositionUtils.getMinCorner(b.getPos1(), b.getPos2());
            BlockPos boxMax = PositionUtils.getMaxCorner(b.getPos1(), b.getPos2());
            p1 = p1 == null ? boxMin : PositionUtils.getMinCorner(p1, boxMin);
            p2 = p2 == null ? boxMax : PositionUtils.getMaxCorner(p2, boxMax);
        }
        this.minPos = p1 != null ? p1 : origin;
        this.maxPos = p2 != null ? p2 : origin;

        var areaSizes = schematic.getAreaSizes();
        for (SubRegionPlacement subPlacement : placement.getAllSubRegionsPlacements()) {
            if (!subPlacement.isEnabled()) continue;
            String name = subPlacement.getName();
            Vec3i size = schematic.getAreaSizeAsVec3i(name);
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
            if (size == null || container == null) continue;

            RegionTransform transform = new RegionTransform(placement, subPlacement, name, size);
            regions.add(new RegionEntry(transform, container));
        }
    }

    public boolean isInAnyRegion(int x, int y, int z) {
        for (RegionEntry entry : regions) {
            if (entry.transform.containsWorld(x, y, z)) return true;
        }
        return false;
    }

    public boolean isInBoundingBox(int x, int y, int z) {
        return x >= minPos.getX() && x <= maxPos.getX() &&
               y >= minPos.getY() && y <= maxPos.getY() &&
               z >= minPos.getZ() && z <= maxPos.getZ();
    }

    public BlockState getBlockStateAt(int x, int y, int z) {
        if (!isInBoundingBox(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        for (RegionEntry entry : regions) {
            if (entry.transform.worldToLocal(x, y, z, localPos)) {
                BlockState state = entry.container.get(localPos.getX(), localPos.getY(), localPos.getZ());
                if (state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID) {
                    continue;
                }

                if (entry.transform.mainMirror != null && entry.transform.mainMirror != net.minecraft.world.level.block.Mirror.NONE) {
                    state = state.mirror(entry.transform.mainMirror);
                }
                if (entry.transform.subMirror != null && entry.transform.subMirror != net.minecraft.world.level.block.Mirror.NONE) {
                    state = state.mirror(entry.transform.subMirror);
                }
                if (entry.transform.combinedRotation != null && entry.transform.combinedRotation != net.minecraft.world.level.block.Rotation.NONE) {
                    state = state.rotate(entry.transform.combinedRotation);
                }

                return state;
            }
        }

        return Blocks.AIR.defaultBlockState();
    }
}

