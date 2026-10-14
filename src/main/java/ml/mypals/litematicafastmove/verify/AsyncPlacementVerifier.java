package ml.mypals.litematicafastmove.verify;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.access.PlacementAccessorRegistry;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncPlacementVerifier {
    private static final ExecutorService VERIFY_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread thread = new Thread(r, "LFMVerifier");
                thread.setDaemon(true);
                return thread;
            }
    );

    private static final AtomicInteger CURRENT_TASK_ID = new AtomicInteger(0);

    public static void submitVerification(SchematicPlacement placement) {
        if (placement == null) return;
        final int taskId = CURRENT_TASK_ID.incrementAndGet();
        AsyncVerifyResult.clear();

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        PlacementBlockAccessor accessor = PlacementAccessorRegistry.getOrCreate(placement);
        if (accessor == null) return;

        var touchedChunks = placement.getTouchedChunks();
        for (ChunkPos chunkPos : touchedChunks) {
            VERIFY_EXECUTOR.submit(() -> {
                if (CURRENT_TASK_ID.get() != taskId) {
                    return;
                }
                verifySingleChunk(level, chunkPos, accessor, taskId);
            });
        }
    }

    private static void verifySingleChunk(ClientLevel level, ChunkPos chunkPos, PlacementBlockAccessor placement, int taskId) {
        ChunkAccess clientChunk = level.getChunkSource().getChunk(chunkPos.x(), chunkPos.z(), false);
        if (clientChunk == null) {
            return;
        }

        int mismatchedCount = 0, missingCount = 0, extraCount = 0;
        List<BlockPos> mismatchedPositions = new ArrayList<>();

        LevelChunkSection[] sections = clientChunk.getSections();
        int minSectionY = clientChunk.getMinSectionY();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            if (CURRENT_TASK_ID.get() != taskId) return;

            LevelChunkSection section = sections[sectionIdx];
            int sectionY = minSectionY + sectionIdx;
            int startY = sectionY << 4;
            int endY = startY + 15;

            if (endY < placement.minPos.getY() || startY > placement.maxPos.getY()) {
                continue;
            }

            boolean isSectionEmpty = (section == null || section.hasOnlyAir());
            for (int y = startY; y <= endY; ++y) {
                if (y < placement.minPos.getY() || y > placement.maxPos.getY()) continue;

                for (int z = 0; z < 16; ++z) {
                    int worldZ = (chunkPos.z() << 4) + z;
                    if (worldZ < placement.minPos.getZ() || worldZ > placement.maxPos.getZ()) continue;

                    for (int x = 0; x < 16; ++x) {
                        int worldX = (chunkPos.x() << 4) + x;
                        if (worldX < placement.minPos.getX() || worldX > placement.maxPos.getX()) continue;

                        pos.set(worldX, y, worldZ);
                        BlockState schemState = placement.getBlockStateAt(worldX, y, worldZ);
                        BlockState clientState = isSectionEmpty ? Blocks.AIR.defaultBlockState() : section.getBlockState(x, y & 15, z);

                        boolean schemAir = schemState.isAir();
                        boolean clientAir = clientState.isAir();

                        if (schemAir && clientAir) {
                            continue;
                        }

                        if (!schemAir && clientAir) {
                            missingCount++;
                            mismatchedPositions.add(pos.immutable());
                        } else if (schemAir) {
                            extraCount++;
                        } else if (!schemState.equals(clientState)) {
                            mismatchedCount++;
                            mismatchedPositions.add(pos.immutable());
                        }
                    }
                }
            }
        }

        AsyncVerifyResult.putResult(chunkPos, new AsyncVerifyResult(chunkPos, mismatchedCount, missingCount, extraCount, mismatchedPositions));
    }
}

