package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslucentSorter {
    private record Resorted(PlacementMesh.GpuLayer layer, ByteBuffer indices, double x, double y, double z) {}

    private static final ExecutorService SORTER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LFMTranslucentSorter");
        thread.setDaemon(true);
        return thread;
    });
    private static final Queue<Resorted> RESORTED = new ConcurrentLinkedQueue<>();
    // 只在排序线程上用
    private static final ByteBufferBuilder SCRATCH = new ByteBufferBuilder(64 * 1024);

    private int budget = FastMoveConfig.TRANSLUCENT_RESORTS_PER_FRAME.getIntegerValue();

    private TranslucentSorter() {}

    static TranslucentSorter forFrame() {
        return new TranslucentSorter();
    }

    void maybeResort(PlacementMesh.GpuLayer layer, double rx, double ry, double rz) {
        PlacementMesh.SortInfo sort = layer.sort();
        if (sort == null || sort.pending || this.budget <= 0) return;

        double dx = rx - sort.lastX, dy = ry - sort.lastY, dz = rz - sort.lastZ;
        double threshold = FastMoveConfig.TRANSLUCENT_RESORT_DISTANCE.getDoubleValue();
        if (!Double.isNaN(sort.lastX) && dx * dx + dy * dy + dz * dz <= threshold * threshold) return;

        this.budget--;
        sort.pending = true;
        int size = layer.indexCount() * layer.indexType().bytes;
        SORTER.submit(() -> {
            ByteBuffer indices = MemoryUtil.memAlloc(size);
            try (ByteBufferBuilder.Result sorted = sort.state.buildSortedIndexBuffer(SCRATCH,
                    VertexSorting.byDistance((float) rx, (float) ry, (float) rz))) {
                if (sorted == null || sorted.byteBuffer().remaining() != size) {
                    throw new IllegalStateException("Sorted index buffer does not match the uploaded one");
                }
                MemoryUtil.memCopy(sorted.byteBuffer(), indices);
                indices.position(0).limit(size);
                RESORTED.add(new Resorted(layer, indices, rx, ry, rz));
            } catch (Throwable e) {
                MemoryUtil.memFree(indices);
                LitematicaFastMove.LOGGER.debug("Failed to resort translucent section", e);
            }
        });
    }

    static void applyResorted() {
        Resorted resorted;
        while ((resorted = RESORTED.poll()) != null) {
            PlacementMesh.GpuLayer layer = resorted.layer();
            if (layer.indexBuffer() != null && !layer.indexBuffer().isClosed()) {
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(layer.indexBuffer().slice(), resorted.indices());
            }
            MemoryUtil.memFree(resorted.indices());
            layer.sort().lastX = resorted.x();
            layer.sort().lastY = resorted.y();
            layer.sort().lastZ = resorted.z();
            layer.sort().pending = false;
        }
    }
}
