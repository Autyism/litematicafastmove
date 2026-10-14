package ml.mypals.litematicafastmove.mixin;

import fi.dy.masa.litematica.render.schematic.BlockModelCacheSchematic;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = BlockModelCacheSchematic.class, remap = false)
public interface BlockModelCacheSchematicAccessor {
    @Invoker("fluidStateModelSet")
    FluidStateModelSet litematicafastmove$fluidStateModelSet();
}
