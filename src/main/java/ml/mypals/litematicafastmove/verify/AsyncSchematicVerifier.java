package ml.mypals.litematicafastmove.verify;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncSchematicVerifier {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            runnable -> {
                Thread thread = new Thread(runnable, "LFMVerifier");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }
    );

    public record ChunkResult(ChunkPos pos, long[] mismatchPositions,
                              BlockState[] expected, BlockState[] found,
      Object2IntOpenHashMap<BlockState> correctCounts, int correctNonAir,
     int schematicBlocks, int clientBlocks) {

    }

    public static final class Session {
        public final SchematicPlacement placement;
        private final PlacementBlockAccessor accessor;
        private final Set<ChunkPos> submitted = new HashSet<>();
        private final Queue<ChunkResult> completed = new ConcurrentLinkedQueue<>();
        private final AtomicInteger running = new AtomicInteger();
        private volatile boolean cancelled;

        public ChunkResult merging;
        public int mergeIndex;

        public Session(SchematicPlacement placement) {
            this.placement = placement;
            this.accessor = new PlacementBlockAccessor(placement);
        }

        public boolean isSubmitted(ChunkPos pos) {
            return this.submitted.contains(pos);
        }

        public void submit(ChunkPos pos, ChunkAccess clientChunk, List<IntBoundingBox> boxes) {
            this.submitted.add(pos);
            this.running.incrementAndGet();
            POOL.submit(() -> {
                try {
                    if (!this.cancelled) {
                        this.completed.add(scan(pos, clientChunk, boxes, this.accessor));
                    }
                } catch (Exception e) {
                    LitematicaFastMove.LOGGER.error("Failed to verify chunk {}", pos, e);
                } finally {
                    this.running.decrementAndGet();
                }
            });
        }

        public ChunkResult pollCompleted() {
            return this.cancelled ? null : this.completed.poll();
        }

        public boolean isIdle() {
            return this.running.get() == 0 && this.completed.isEmpty() && this.merging == null;
        }

        public void cancel() {
            this.cancelled = true;
            this.completed.clear();
            this.merging = null;
        }
    }

    private static ChunkResult scan(ChunkPos pos, ChunkAccess clientChunk, List<IntBoundingBox> boxes, PlacementBlockAccessor accessor) {
        LongArrayList positions = new LongArrayList();
        List<BlockState> expected = new ArrayList<>();
        List<BlockState> found = new ArrayList<>();
        Object2IntOpenHashMap<BlockState> correct = new Object2IntOpenHashMap<>();
        int correctNonAir = 0, schematicBlocks = 0, clientBlocks = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (IntBoundingBox box : boxes) {
            for (int y = box.minY(); y <= box.maxY(); ++y) {
                for (int z = box.minZ(); z <= box.maxZ(); ++z) {
                    for (int x = box.minX(); x <= box.maxX(); ++x) {
                        BlockState stateSchematic = accessor.getBlockStateAt(x, y, z);
                        BlockState stateClient = clientChunk.getBlockState(mutable.set(x, y, z));
                        boolean schematicAir = stateSchematic.isAir();
                        boolean clientAir = stateClient.isAir();

                        if (!schematicAir) schematicBlocks++;
                        if (!clientAir) clientBlocks++;

                        if (stateClient != stateSchematic && (!clientAir || !schematicAir)) {
                            positions.add(BlockPos.asLong(x, y, z));
                            expected.add(stateSchematic);
                            found.add(stateClient);
                        } else {
                            correct.addTo(stateClient, 1);
                            if (!schematicAir) correctNonAir++;
                        }
                    }
                }
            }
        }

        return new ChunkResult(pos, positions.toLongArray(), expected.toArray(new BlockState[0]), found.toArray(new BlockState[0]),
                correct, correctNonAir, schematicBlocks, clientBlocks);
    }
}
