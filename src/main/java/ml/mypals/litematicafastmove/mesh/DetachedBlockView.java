package ml.mypals.litematicafastmove.mesh;

import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
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
    private boolean hasUnclippedBlocks;
    private Direction.Axis clipAxis;
    private int clipMin, clipMax;

    DetachedBlockView(PlacementBlockAccessor accessor, Level worldSchematic, ClientLevel clientLevel) {
        this.accessor = accessor;
        this.lightWorld = worldSchematic;
        this.clientLevel = clientLevel;
    }


    void setClip(Direction.Axis axis, int min, int max) {
        this.clipAxis = axis;
        this.clipMin = min;
        this.clipMax = max;
    }

    boolean hasUnclippedBlocks() {
        return this.hasUnclippedBlocks;
    }

    private boolean isClipped(int x, int y, int z) {
        if (this.clipAxis == null) return false;
        int v = this.clipAxis.choose(x, y, z);
        return v < this.clipMin || v > this.clipMax;
    }

    boolean loadSection(int sx, int sy, int sz) {
        this.baseX = (sx << 4) - 1;
        this.baseY = (sy << 4) - 1;
        this.baseZ = (sz << 4) - 1;
        boolean hasBlock = false;
        this.hasUnclippedBlocks = false;
        int i = 0;
        for (int dy = 0; dy < SIZE; dy++) {
            for (int dz = 0; dz < SIZE; dz++) {
                for (int dx = 0; dx < SIZE; dx++) {
                    int x = this.baseX + dx, y = this.baseY + dy, z = this.baseZ + dz;
                    BlockState raw = this.accessor.getBlockStateAt(x, y, z);
                    BlockState state = this.isClipped(x, y, z) ? AIR : raw;
                    if (!this.hasUnclippedBlocks && !raw.isAir() && dx > 0 && dx < 17 && dy > 0 && dy < 17 && dz > 0 && dz < 17) {
                        this.hasUnclippedBlocks = true;
                    }
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
        if (this.isClipped(pos.getX(), pos.getY(), pos.getZ())) return AIR;
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
    public float getShade(@NonNull Direction direction, boolean shade) {
        return this.clientLevel.getShade(direction, shade);
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
