package ml.mypals.litematicafastmove.mixin;

import com.google.common.collect.ArrayListMultimap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.IgnoreBlockRegistry;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerRange;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
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
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class MixinSchematicVerifier extends TaskBase {

    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> missingBlocksPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> extraBlocksPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongBlocksPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongStatesPositions;
    @Shadow @Final private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> diffBlocksPositions;
    @Shadow @Final private Object2ObjectOpenHashMap<BlockPos, SchematicVerifier.BlockMismatch> blockMismatches;
    @Shadow @Final private HashSet<Pair<BlockState, BlockState>> ignoredMismatches;
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
    @Shadow private IgnoreBlockRegistry ignoreBlockRegistry;

    @Shadow public abstract void updateRequiredChunksStringList();

    @Unique private AsyncSchematicVerifier.Session litematicafastmove$session;

    @Inject(method = "verifyChunks", at = @At("HEAD"), cancellable = true)
    private void verifyChunksAsync(ProfilerFiller profiler, CallbackInfoReturnable<Boolean> cir) {
        if (!FastMoveConfig.ENABLE_ASYNC_VERIFY.getBooleanValue() || this.schematicPlacement == null) return;

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
            session = this.litematicafastmove$session = new AsyncSchematicVerifier.Session(
                    this.schematicPlacement, this.ignoreBlockRegistry, this.ignoredMismatches);
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
            if (!WorldUtils.isClientChunkLoaded(this.worldClient, pos.x, pos.z)) continue;

            List<IntBoundingBox> boxes = new ArrayList<>();
            for (IntBoundingBox box : this.schematicPlacement.getBoxesWithinChunk(pos.x, pos.z).values()) {
                IntBoundingBox clamped = ranged ? litematicafastmove$clamp(box, range) : box;
                if (clamped != null) boxes.add(clamped);
            }

            session.submit(pos, this.worldClient.getChunk(pos.x, pos.z), boxes);
        }
    }

    @Unique
    private static IntBoundingBox litematicafastmove$clamp(IntBoundingBox box, LayerRange range) {
        Direction.Axis axis = range.getAxis();
        int min = range.getLayerMin();
        int max = range.getLayerMax();
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
     * 在本 tick 剩余预算内合并扫描结果：工作线程已完成判定并按 (类型, 期望 -> 实际) 分组，
     * 这里只做批量写入；扫描期间新加入忽略列表的组在写入前跳过
     */
    @Unique
    private boolean litematicafastmove$mergeResults(AsyncSchematicVerifier.Session session) {
        long deadline = DataManager.getClientTickStartTime() + FastMoveConfig.VERIFIER_MERGE_MILLIS_PER_TICK.getIntegerValue() * 1_000_000L;
        boolean changed = false;

        while (System.nanoTime() < deadline) {
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

            for (var typeEntry : result.mismatches().entrySet()) {
                ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map = this.litematicafastmove$getMap(typeEntry.getKey());
                if (map == null) continue;

                for (var groupEntry : typeEntry.getValue().entrySet()) {
                    if (this.ignoredMismatches.contains(groupEntry.getKey())) continue;

                    AsyncSchematicVerifier.MismatchGroup group = groupEntry.getValue();
                    map.putAll(groupEntry.getKey(), group.positions());
                    for (BlockPos pos : group.positions()) {
                        this.blockMismatches.put(pos, group.mismatch());
                    }
                    ItemUtils.setItemForBlock(this.worldClient, itemPos, group.mismatch().stateFound);
                }
            }

            if (this.worldSchematic != null) {
                for (BlockState state : result.schematicMismatchStates()) {
                    ItemUtils.setItemForBlock(this.worldSchematic, itemPos, state);
                }
            }

            this.requiredChunks.remove(result.pos());
            changed = true;
        }

        return changed;
    }

    @Unique
    private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> litematicafastmove$getMap(SchematicVerifier.MismatchType type) {
        return switch (type) {
            case MISSING -> this.missingBlocksPositions;
            case EXTRA -> this.extraBlocksPositions;
            case WRONG_BLOCK -> this.wrongBlocksPositions;
            case WRONG_STATE -> this.wrongStatesPositions;
            case DIFF_BLOCK -> this.diffBlocksPositions;
            default -> null;
        };
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
            target = "Lfi/dy/masa/litematica/world/WorldSchematic;hasChunkAt(Lnet/minecraft/core/BlockPos;)Z", remap = true))
    private boolean recheckWithoutWorldSchematic(WorldSchematic world, BlockPos pos, Operation<Boolean> original) {
        if (FastMoveConfig.ENABLE_ASYNC_VERIFY.getBooleanValue() && this.schematicPlacement != null) return true;
        return original.call(world, pos);
    }

    @WrapOperation(method = "checkChangedPositions", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/litematica/world/WorldSchematic;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", remap = true))
    private BlockState recheckFromPlacement(WorldSchematic world, BlockPos pos, Operation<BlockState> original) {
        if (FastMoveConfig.ENABLE_ASYNC_VERIFY.getBooleanValue() && this.schematicPlacement != null) {
            PlacementBlockAccessor accessor = PlacementAccessorRegistry.getOrCreate(this.schematicPlacement);
            if (accessor != null) return accessor.getBlockStateAt(pos.getX(), pos.getY(), pos.getZ());
        }
        return original.call(world, pos);
    }
}
