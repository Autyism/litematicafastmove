package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherBuffers;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;


@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public abstract class MixinChunkRendererSchematicVbo {

    @Shadow @Final protected BlockPos.MutableBlockPos position;

    @Unique private volatile List<IntBoundingBox> litematicafastmove$detachedBoxes = List.of();

    @Inject(method = "rebuildWorldView", at = @At("RETURN"))
    private void onRebuildWorldView(CallbackInfo ci) {
        int chunkX = this.position.getX() >> 4;
        int chunkZ = this.position.getZ() >> 4;
        List<IntBoundingBox> detached = new ArrayList<>();

        for (SchematicPlacementManager.PlacementPart part : DataManager.getSchematicPlacementManager().getPlacementPartsInChunk(chunkX, chunkZ)) {
            if (PlacementMeshManager.isDetached(part.placement)) {
                detached.add(part.bb);
            }
        }

        this.litematicafastmove$detachedBoxes = detached.isEmpty() ? List.of() : detached;
    }

    @WrapOperation(method = "renderBlocksAndOverlay", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/IWorldSchematicRenderer;renderBlock(Lfi/dy/masa/litematica/render/schematic/BlockModelRendererSchematic;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;Lfi/dy/masa/litematica/render/schematic/IBlockOutputSchematic;)Z"))
    private boolean skipDetachedBlock(IWorldSchematicRenderer renderer, BlockModelRendererSchematic blockRenderer, BlockAndTintGetter world,
                                      BlockState state, BlockPos pos, Vec3 offset, IBlockOutputSchematic output, Operation<Boolean> original) {
        if (this.litematicafastmove$isDetached(pos)) return false;
        return original.call(renderer, blockRenderer, world, state, pos, offset, output);
    }

    @WrapOperation(method = "renderBlocksAndOverlay", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/IWorldSchematicRenderer;renderFluid(Lfi/dy/masa/litematica/render/schematic/FluidModelRendererSchematic;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;F)Z"))
    private boolean skipDetachedFluid(IWorldSchematicRenderer renderer, FluidModelRendererSchematic fluidRenderer, BlockAndTintGetter world,
                                      BlockState state, FluidState fluidState, BlockPos pos, FluidRenderer.Output output, float offsetY,
                                      Operation<Boolean> original) {
        if (this.litematicafastmove$isDetached(pos)) return false;
        return original.call(renderer, fluidRenderer, world, state, fluidState, pos, output, offsetY);
    }

    @WrapOperation(method = "renderBlocksAndOverlay", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/schematic/ChunkRendererSchematicVbo;renderOverlay(Lfi/dy/masa/litematica/util/OverlayType;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ZLfi/dy/masa/litematica/render/schematic/ChunkRenderDataSchematic;Lfi/dy/masa/litematica/render/schematic/ChunkMeshDataSchematic;Lfi/dy/masa/litematica/render/schematic/ChunkRenderDispatcherBuffers;)V"))
    private void skipDetachedOverlay(ChunkRendererSchematicVbo self, OverlayType type, BlockPos pos, BlockState state, boolean missing,
                                     ChunkRenderDataSchematic data, ChunkMeshDataSchematic meshData, ChunkRenderDispatcherBuffers pack,
                                     Operation<Void> original) {
        if (this.litematicafastmove$isDetached(pos)) return;
        original.call(self, type, pos, state, missing, data, meshData, pack);
    }

    @Unique
    private boolean litematicafastmove$isDetached(BlockPos pos) {
        List<IntBoundingBox> boxes = this.litematicafastmove$detachedBoxes;
        if (boxes.isEmpty()) return false;

        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (IntBoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY() && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }
}
