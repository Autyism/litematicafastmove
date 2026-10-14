package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.schematic.placement.PlacementManagerTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PlacementManagerTask.class, remap = false)
public interface PlacementManagerTaskAccessor {
    @Accessor("chunkX")
    int litematicafastmove$getChunkX();

    @Accessor("chunkZ")
    int litematicafastmove$getChunkZ();
}
