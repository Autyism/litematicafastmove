package ml.mypals.litematicafastmove.mesh;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


final class PlacementMesh {
    record CpuLayer(ChunkSectionLayer layer, OverlayRenderType overlay, ByteBuffer vertices, int indexCount) {
        void free() {
            MemoryUtil.memFree(this.vertices);
        }
    }
    record CpuSection(long key, BlockPos origin, List<CpuLayer> layers) {}
    record GpuLayer(ChunkSectionLayer layer, OverlayRenderType overlay, GpuBuffer vertexBuffer, int indexCount) {}
    record GpuSection(long key, BlockPos origin, List<GpuLayer> layers) {
        void close() {
            for (GpuLayer layer : this.layers) {
                layer.vertexBuffer.close();
            }
        }
    }


    static final class BuiltPlacementBuffer {
        final int generation;
        final BlockPos buildOrigin;
        final Set<Long> keys;
        final List<CpuSection> pending;
        final List<GpuSection> uploaded = new ArrayList<>();
        int nextToUpload;

        BuiltPlacementBuffer(int generation, BlockPos buildOrigin, Set<Long> keys, List<CpuSection> pending) {
            this.generation = generation;
            this.buildOrigin = buildOrigin;
            this.keys = keys;
            this.pending = pending;
        }

        boolean isFull() {
            return this.keys == null;
        }

        boolean isFullyUploaded() {
            return this.nextToUpload >= this.pending.size();
        }

        void discard() {
            for (int i = this.nextToUpload; i < this.pending.size(); i++) {
                this.pending.get(i).layers().forEach(CpuLayer::free);
            }
            this.nextToUpload = this.pending.size();
            this.uploaded.forEach(GpuSection::close);
            this.uploaded.clear();
        }
    }

    volatile int requestedGeneration;
    volatile BlockPos targetBuildOrigin;
    volatile PlacementBlockAccessor targetAccessor;

    //RenderThread only
    final Map<Long, GpuSection> active = new HashMap<>();
    int activeGeneration;
    BlockPos activeBuildOrigin;
    BuiltPlacementBuffer uploadingFull;
    final List<BuiltPlacementBuffer> deferredPartials = new ArrayList<>();

    void close() {
        this.active.values().forEach(GpuSection::close);
        this.active.clear();
        if (this.uploadingFull != null) {
            this.uploadingFull.discard();
            this.uploadingFull = null;
        }
        this.deferredPartials.forEach(BuiltPlacementBuffer::discard);
        this.deferredPartials.clear();
    }
}
