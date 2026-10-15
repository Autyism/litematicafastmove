package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import ml.mypals.litematicafastmove.access.PlacementAccessorRegistry;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.verify.AsyncSchematicVerifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class MixinSchematicVerifier extends TaskBase {
    @Unique private static final long TICK_BUDGET_NANOS = 50_000_000L;

    @Shadow @Final private Object2IntOpenHashMap<BlockState> correctStateCounts;
    @Shadow @Final private Set<ChunkPos> requiredChunks;
    @Shadow private ClientLevel worldClient;
    @Shadow private WorldSchematic worldSchematic;
    @Shadow private SchematicPlacement schematicPlacement;
    @Shadow private boolean verificationStarted;
    @Shadow private boolean verificationActive;
    @Shadow private int schematicBlocks;
    @Shadow private int clientBlocks;
    @Shadow private int correctStatesCount;

    @Shadow private void checkBlockStates(int x, int y, int z, BlockState stateSchematic, BlockState stateClient) {}
    @Shadow public abstract void updateRequiredChunksStringList();

    @Unique private AsyncSchematicVerifier.Session litematicafastmove$session;

    @Inject(method = "verifyChunks", at = @At("HEAD"), cancellable = true)
    private void verifyChunksAsync(ProfilerFiller profiler, CallbackInfoReturnable<Boolean> cir) {
        if (!FastMoveConfig.ENABLE_ASYNC_VERIFY || this.schematicPlacement == null) return;

        profiler.push("verify_chunks_async");
        cir.setReturnValue(this.litematicafastmove$verifyChunks());
        profiler.pop();
    }

    @Unique
    private boolean litematicafastmove$verifyChunks() {
        if (!this.verificationActive) return true;

        AsyncSchematicVerifier.Session session = this.litematicafastmove$session;
        if (session == null || session.placement != this.schematicPlacement) {
            if (session != null) session.cancel();
            session = this.litematicafastmove$session = new AsyncSchematicVerifier.Session(this.schematicPlacement);
        }

        this.litematicafastmove$submitReadyChunks(session);

        boolean changed = this.litematicafastmove$mergeResults(session);
        if (changed) {
            this.updateRequiredChunksStringList();
        }

        if (this.requiredChunks.isEmpty() && session.isIdle()) {
            this.litematicafastmove$session = null;
            this.verificationActive = false;
            this.verificationStarted = false;
            this.finished = true;
            this.notifyListener();
        }

        return !this.verificationActive;
    }

    @Unique
    private void litematicafastmove$submitReadyChunks(AsyncSchematicVerifier.Session session) {
        LayerRange range = DataManager.getRenderLayerRange();
        boolean ranged = this.schematicPlacement.getSchematicVerifierType() == BlockInfoListType.RENDER_LAYERS;

        for (ChunkPos pos : this.requiredChunks) {
            if (session.isSubmitted(pos)) continue;
            boolean loaded = true;
            for (int cx = pos.x() - 1; cx <= pos.x() + 1 && loaded; ++cx) {
                for (int cz = pos.z() - 1; cz <= pos.z() + 1; ++cz) {
                    if (!WorldUtils.isClientChunkLoaded(this.worldClient, cx, cz)) {
                        loaded = false;
                        break;
                    }
                }
            }
            if (!loaded) continue;

            List<IntBoundingBox> boxes = new ArrayList<>();
            for (IntBoundingBox box : this.schematicPlacement.getBoxesWithinChunk(pos.x(), pos.z()).values()) {
                IntBoundingBox clamped = ranged ? litematicafastmove$clamp(box, range) : box;
                if (clamped != null) boxes.add(clamped);
            }

            session.submit(pos, this.worldClient.getChunk(pos.x(), pos.z()), boxes);
        }
    }

    @Unique
    private static IntBoundingBox litematicafastmove$clamp(IntBoundingBox box, LayerRange range) {
        Direction.Axis axis = range.getAxis();
        int min = range.getMinLayerBoundary();
        int max = range.getMaxLayerBoundary();
        int minX = axis == Direction.Axis.X ? Math.max(box.minX(), min) : box.minX();
        int minY = axis == Direction.Axis.Y ? Math.max(box.minY(), min) : box.minY();
        int minZ = axis == Direction.Axis.Z ? Math.max(box.minZ(), min) : box.minZ();
        int maxX = axis == Direction.Axis.X ? Math.min(box.maxX(), max) : box.maxX();
        int maxY = axis == Direction.Axis.Y ? Math.min(box.maxY(), max) : box.maxY();
        int maxZ = axis == Direction.Axis.Z ? Math.min(box.maxZ(), max) : box.maxZ();
        if (minX > maxX || minY > maxY || minZ > maxZ) return null;
        return new IntBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 在本 tick 剩余预算内合并扫描结果：计数直接累加，不同的位置逐条交给原版 checkBlockStates 分类
     * (保留忽略列表、作物年龄等原版规则)
     */
    @Unique
    private boolean litematicafastmove$mergeResults(AsyncSchematicVerifier.Session session) {
        long deadline = DataManager.getClientTickStartTime() + TICK_BUDGET_NANOS;
        boolean changed = false;

        while (System.nanoTime() < deadline) {
            if (session.merging == null) {
                AsyncSchematicVerifier.ChunkResult result = session.pollCompleted();
                if (result == null) break;

                BlockPos itemPos = new BlockPos(result.pos().getMinBlockX(), 0, result.pos().getMinBlockZ());
                for (Object2IntMap.Entry<BlockState> entry : result.correctCounts().object2IntEntrySet()) {
                    this.correctStateCounts.addTo(entry.getKey(), entry.getIntValue());
                    ItemUtils.setItemForBlock(this.worldClient, itemPos, entry.getKey());
                }
                this.correctStatesCount += result.correctNonAir();
                this.schematicBlocks += result.schematicBlocks();
                this.clientBlocks += result.clientBlocks();

                session.merging = result;
                session.mergeIndex = 0;
            }

            AsyncSchematicVerifier.ChunkResult result = session.merging;
            long[] positions = result.mismatchPositions();
            int i = session.mergeIndex;

            while (i < positions.length) {
                long packed = positions[i];
                this.checkBlockStates(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed), result.expected()[i], result.found()[i]);
                i++;
                if ((i & 255) == 0 && System.nanoTime() >= deadline) break;
            }

            session.mergeIndex = i;
            if (i >= positions.length) {
                this.requiredChunks.remove(result.pos());
                session.merging = null;
                changed = true;
            }
        }

        return changed;
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void cancelAsyncSession(CallbackInfo ci) {
        if (this.litematicafastmove$session != null) {
            this.litematicafastmove$session.cancel();
            this.litematicafastmove$session = null;
        }
    }

    // 方块变化后的复查：投影方块同样直接从投影容器读取
    @WrapOperation(method = "checkChangedPositions", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/world/WorldSchematic;hasChunkAt(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean recheckWithoutWorldSchematic(WorldSchematic world, BlockPos pos, Operation<Boolean> original) {
        if (FastMoveConfig.ENABLE_ASYNC_VERIFY && this.schematicPlacement != null) return true;
        return original.call(world, pos);
    }

    @WrapOperation(method = "checkChangedPositions", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/world/WorldSchematic;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState recheckFromPlacement(WorldSchematic world, BlockPos pos, Operation<BlockState> original) {
        if (FastMoveConfig.ENABLE_ASYNC_VERIFY && this.schematicPlacement != null) {
            PlacementBlockAccessor accessor = PlacementAccessorRegistry.getOrCreate(this.schematicPlacement);
            if (accessor != null) return accessor.getBlockStateAt(pos.getX(), pos.getY(), pos.getZ());
        }
        return original.call(world, pos);
    }
}
