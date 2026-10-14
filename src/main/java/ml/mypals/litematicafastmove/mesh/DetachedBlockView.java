package ml.mypals.litematicafastmove.mesh;

import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.NonNull;


final class DetachedBlockView implements BlockAndTintGetter {
    private static final int SIZE = 18;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final PlacementBlockAccessor accessor;
    private final Level lightWorld;
    private final ClientLevel clientLevel;
    private final BlockState[] blockCache = new BlockState[SIZE * SIZE * SIZE];
    //private final BlockEntity[] beCache = new BlockEntity[SIZE * SIZE * SIZE];
    private int baseX, baseY, baseZ;

    DetachedBlockView(PlacementBlockAccessor accessor, Level worldSchematic, ClientLevel clientLevel) {
        this.accessor = accessor;
        this.lightWorld = worldSchematic;
        this.clientLevel = clientLevel;
    }


    boolean loadSection(int sx, int sy, int sz) {
        this.baseX = (sx << 4) - 1;
        this.baseY = (sy << 4) - 1;
        this.baseZ = (sz << 4) - 1;
        boolean hasBlock = false;
        int i = 0;
        for (int dy = 0; dy < SIZE; dy++) {
            for (int dz = 0; dz < SIZE; dz++) {
                for (int dx = 0; dx < SIZE; dx++) {
                    BlockState state = this.accessor.getBlockStateAt(this.baseX + dx, this.baseY + dy, this.baseZ + dz);
                    this.blockCache[i++] = state;
                    if (!hasBlock && !state.isAir() && dx > 0 && dx < 17 && dy > 0 && dy < 17 && dz > 0 && dz < 17) {
                        hasBlock = true;
                    }
                }
            }
        }
        return hasBlock;
    }

    @Override
    public @NonNull BlockState getBlockState(BlockPos pos) {
        int dx = pos.getX() - this.baseX;
        int dy = pos.getY() - this.baseY;
        int dz = pos.getZ() - this.baseZ;
        if (dx >= 0 && dx < SIZE && dy >= 0 && dy < SIZE && dz >= 0 && dz < SIZE) {
            return this.blockCache[(dy * SIZE + dz) * SIZE + dx];
        }
        BlockState state = this.accessor.getBlockStateAt(pos.getX(), pos.getY(), pos.getZ());
        return state != null ? state : AIR;
    }

    @Override
    public @NonNull FluidState getFluidState(@NonNull BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(@NonNull BlockPos pos) {
        return null;
    }

    @Override
    public @NonNull LevelLightEngine getLightEngine() {
        return this.lightWorld.getLightEngine();
    }

    @Override
    public @NonNull CardinalLighting cardinalLighting() {
        return this.clientLevel.cardinalLighting();
    }

    @Override
    public int getBlockTint(@NonNull BlockPos pos, @NonNull ColorResolver colorResolver) {
        return this.clientLevel.getBlockTint(pos, colorResolver);
    }

    @Override
    public int getHeight() {
        return this.lightWorld.getHeight();
    }

    @Override
    public int getMinY() {
        return this.lightWorld.getMinY();
    }
}
