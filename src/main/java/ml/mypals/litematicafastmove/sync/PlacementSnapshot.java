package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

public record PlacementSnapshot(SchematicPlacement placement, BlockPos origin, Rotation rotation, Mirror mirror,
                                boolean enabled, boolean renderingEnabled, List<SubRegionSnapshot> subRegions) {

    public record SubRegionSnapshot(String name, BlockPos pos, Rotation rotation, Mirror mirror, boolean enabled) {}

    public static PlacementSnapshot of(SchematicPlacement placement) {
        List<SubRegionSnapshot> subs = new ArrayList<>();
        for (SubRegionPlacement sub : placement.getAllSubRegionsPlacements()) {
            subs.add(new SubRegionSnapshot(sub.getName(), sub.getPos(), sub.getRotation(), sub.getMirror(), sub.isEnabled()));
        }
        return new PlacementSnapshot(placement, placement.getOrigin(), placement.getRotation(), placement.getMirror(),
                placement.isEnabled(), placement.isRenderingEnabled(), subs);
    }

    public boolean isPureTranslationTo(SchematicPlacement current) {
        if (current != this.placement || current.getOrigin().equals(this.origin)) {
            return false;
        }
        PlacementSnapshot now = of(current);
        return this.rotation == now.rotation && this.mirror == now.mirror &&
               this.enabled == now.enabled && this.renderingEnabled == now.renderingEnabled &&
               this.subRegions.equals(now.subRegions);
    }
}
