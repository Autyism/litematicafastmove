package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


final class PlacementMesh {
    // 层范围裁剪：沿 axis 只保留区段内相对坐标 [lo, hi]；null = 不裁剪，lo > hi = 整段裁掉
    record Clip(Direction.Axis axis, int lo, int hi) {
        boolean isEmpty() {
            return this.lo > this.hi;
        }
    }

    record CpuLayer(ChunkSectionLayer layer, OverlayRenderType overlay, ByteBuffer vertices, int indexCount,
                    ByteBuffer indices, IndexType indexType, MeshData.SortState sortState) {
        void free() {
            MemoryUtil.memFree(this.vertices);
            if (this.indices != null) {
                MemoryUtil.memFree(this.indices);
            }
        }
    }
    // clientEmpty：构建时对应的真实世界区域 (外扩 1 格) 全是空气
    record CpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip, List<CpuLayer> layers) {}

    static final class SortInfo {
        final MeshData.SortState state;
        double lastX, lastY, lastZ;
        boolean pending;

        SortInfo(MeshData.SortState state, double x, double y, double z) {
            this.state = state;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }
    }

    record GpuLayer(ChunkSectionLayer layer, OverlayRenderType overlay, GpuBuffer vertexBuffer, int indexCount,
                    GpuBuffer indexBuffer, IndexType indexType, SortInfo sort) {
        void close() {
            this.vertexBuffer.close();
            if (this.indexBuffer != null) {
                this.indexBuffer.close();
            }
        }
    }
    // hasBlocks：区段内有投影方块，即使被层范围裁空也保留，范围变化时据此判断是否重建
    record GpuSection(long key, BlockPos origin, boolean hasBlocks, boolean clientEmpty, Clip clip, List<GpuLayer> layers) {
        void close() {
            for (GpuLayer layer : this.layers) {
                layer.close();
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
    final ArrayDeque<BuiltPlacementBuffer> uploadingPartials = new ArrayDeque<>();
    final Set<Long> clipRebuildPending = new HashSet<>();

    void close() {
        this.active.values().forEach(GpuSection::close);
        this.active.clear();
        if (this.uploadingFull != null) {
            this.uploadingFull.discard();
            this.uploadingFull = null;
        }
        this.deferredPartials.forEach(BuiltPlacementBuffer::discard);
        this.deferredPartials.clear();
        this.uploadingPartials.forEach(BuiltPlacementBuffer::discard);
        this.uploadingPartials.clear();
        this.clipRebuildPending.clear();
    }
}
