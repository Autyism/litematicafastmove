package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherBuffers;
import fi.dy.masa.litematica.render.schematic.ChunkRenderTaskSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.util.IntBoundingBox;
import ml.mypals.litematicafastmove.mesh.PlacementBlockEntities;
import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public abstract class MixinChunkRendererSchematicVbo {

    @Shadow @Final protected BlockPos.MutableBlockPos position;
    @Shadow @Final protected List<IntBoundingBox> boxes;
    @Shadow protected ChunkCacheSchematic schematicWorldView;

    @Shadow private void addBlockEntity(BlockState state, BlockPos pos, ChunkMeshDataSchematic chunkMeshData) {}

    @Inject(method = "rebuildWorldView", at = @At("RETURN"))
    private void excludeTakenOverPlacements(CallbackInfo ci) {
        int chunkX = this.position.getX() >> 4;
        int chunkZ = this.position.getZ() >> 4;

        synchronized (this.boxes) {
            this.boxes.clear();
            for (SchematicPlacementManager.PlacementPart part : DataManager.getSchematicPlacementManager().getPlacementPartsInChunk(chunkX, chunkZ)) {
                if (!PlacementMeshManager.isDetached(part.placement)) {
                    this.boxes.add(part.bb);
                }
            }
        }
    }

    @Inject(method = "rebuildChunk", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/render/schematic/ChunkMeshDataSchematic;setTimeBuilt(J)V"))
    private void addTakenOverBlockEntities(ChunkRenderTaskSchematic task, ChunkRenderDispatcherBuffers pack, CallbackInfo ci,
                                           @Local ChunkMeshDataSchematic chunkMeshData) {
        if (this.schematicWorldView == null) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        PlacementBlockEntities.forEachInChunk(this.position.getX() >> 4, this.position.getZ() >> 4, packed -> {
            pos.set(packed);
            BlockState state = this.schematicWorldView.getBlockState(pos);
            if (state.hasBlockEntity()) {
                this.addBlockEntity(state, pos.immutable(), chunkMeshData);
            }
        });
    }

    @WrapMethod(method = "rebuildChunk")
    private void plainFormatRebuild(ChunkRenderTaskSchematic task, ChunkRenderDispatcherBuffers pack, Operation<Void> original) {
        SchematicExit.plainFormat(() -> original.call(task, pack));
    }

    @WrapMethod(method = "resortTransparency")
    private void plainFormatResort(ChunkRenderTaskSchematic task, ChunkRenderDispatcherBuffers pack, Operation<Void> original) {
        SchematicExit.plainFormat(() -> original.call(task, pack));
    }
}
