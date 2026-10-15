package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.render.schematic.BlockModelCacheSchematic;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.config.ThreadPools;
import ml.mypals.litematicafastmove.mixin.BlockModelCacheSchematicAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MeshSectionBuilder {
    private static final class MeshBuilderThread extends Thread {
        MeshBuilderThread(Runnable r) {
            super(r, "LFMMeshBuilder");
            this.setDaemon(true);
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    private static final ThreadPoolExecutor BUILDER = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            FastMoveConfig.meshBuilderThreads(), MeshBuilderThread::new);

    public static void applyThreadCount() {
        ThreadPools.resize(BUILDER, FastMoveConfig.meshBuilderThreads());
    }

    private MeshSectionBuilder() {}

    public static boolean isBuilderThread() {
        return Thread.currentThread() instanceof MeshBuilderThread;
    }

    static void submit(SchematicPlacement placement, PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                       BlockPos buildOrigin, Set<Long> keys, BlockPos clientDelta, WorldSchematic world, ClientLevel clientLevel,
                       Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt) {
        LayerRangeClip.Range range = LayerRangeClip.snapshot();
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();

        BUILDER.submit(() -> {
            try {
                long start = System.nanoTime();
                List<PlacementMesh.CpuSection> sections = build(mesh, generation, accessor, keys, clientDelta, range, camera, world, clientLevel);
                if (sections != null) {
                    if (keys == null) {
                        LitematicaFastMove.LOGGER.debug("Built detached mesh for '{}' in {} ms ({} sections)",
                                placement.getName(), (System.nanoTime() - start) / 1_000_000, sections.size());
                    }
                    onBuilt.accept(new PlacementMesh.BuiltPlacementBuffer(generation, buildOrigin, keys, sections));
                }
            } catch (Throwable e) {
                LitematicaFastMove.LOGGER.error("Failed to build mesh for placement '{}'", placement.getName(), e);
            }
        });
    }

    private static List<PlacementMesh.CpuSection> build(PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                                                        Set<Long> keys, BlockPos clientDelta, LayerRangeClip.Range range, Vec3 camera,
                                                        WorldSchematic world, ClientLevel clientLevel) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        List<Long> sectionKeys = new ArrayList<>();
        if (keys != null) {
            sectionKeys.addAll(keys);
        } else {
            for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
                    for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
                        sectionKeys.add(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }

        List<PlacementMesh.CpuSection> sections = new ArrayList<>();
        DetachedBlockView view = new DetachedBlockView(accessor, world, clientLevel);
        EnumMap<ChunkSectionLayer, ByteBufferBuilder> allocators = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        ByteBufferBuilder sortAllocator = new ByteBufferBuilder(64 * 1024);

        Direction.Axis clipAxis = range != null ? range.axis() : null;
        int clipMin = Integer.MIN_VALUE, clipMax = Integer.MAX_VALUE;
        if (range != null) {
            int d = clipAxis.choose(clientDelta.getX(), clientDelta.getY(), clientDelta.getZ());
            clipMin = range.min() - d;
            clipMax = range.max() - d;
            view.setClip(clipAxis, clipMin, clipMax);
        }

        BlockModelRendererSchematic blockRenderer = new BlockModelRendererSchematic();
        blockRenderer.toggleAO(true);
        blockRenderer.toggleCulling(true);
        blockRenderer.reload();
        blockRenderer.enableCache();
        FluidModelRendererSchematic fluidRenderer = new FluidModelRendererSchematic(
                ((BlockModelCacheSchematicAccessor) BlockModelCacheSchematic.INSTANCE).litematicafastmove$fluidStateModelSet());

        boolean translucent = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        boolean renderFluids = Configs.Visuals.ENABLE_SCHEMATIC_FLUIDS.getBooleanValue();
        boolean renderColliding = Configs.Visuals.RENDER_COLLIDING_SCHEMATIC_BLOCKS.getBooleanValue();

        FluidRenderer.Output fluidOutput = layer -> builder(builders, allocators, layer);
        IBlockOutputSchematic blockOutput = (bx, by, bz, quad, inst) ->
                builder(builders, allocators, translucent ? ChunkSectionLayer.TRANSLUCENT : quad.materialInfo().layer())
                        .putBlockBakedQuad(bx, by, bz, quad, inst);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos clientPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos overlayClientPos = new BlockPos.MutableBlockPos();

        EnumMap<OverlayRenderType, ByteBufferBuilder> overlayAllocators = new EnumMap<>(OverlayRenderType.class);
        EnumMap<OverlayRenderType, BufferBuilder> overlayBuilders = new EnumMap<>(OverlayRenderType.class);
        OverlayMeshBuilder overlay = OverlayMeshBuilder.isEnabled()
                ? new OverlayMeshBuilder(view,
                        p -> clientLevel.getBlockState(overlayClientPos.setWithOffset(p, clientDelta)),
                        type -> overlayBuilders.computeIfAbsent(type, t -> new BufferBuilder(
                                overlayAllocators.computeIfAbsent(t, k -> new ByteBufferBuilder(256 * 1024)),
                                t.topology(), t.vertexFormat())))
                : null;

        try {
            for (long key : sectionKeys) {
                if (mesh.requestedGeneration != generation) {
                    sections.forEach(s -> s.layers().forEach(PlacementMesh.CpuLayer::free));
                    return null;
                }

                int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
                BlockPos sectionOrigin = new BlockPos(sx << 4, sy << 4, sz << 4);
                int x0 = Math.max(sx << 4, min.getX()), x1 = Math.min((sx << 4) + 15, max.getX());
                int y0 = Math.max(sy << 4, min.getY()), y1 = Math.min((sy << 4) + 15, max.getY());
                int z0 = Math.max(sz << 4, min.getZ()), z1 = Math.min((sz << 4) + 15, max.getZ());
                PlacementMesh.Clip clip = LayerRangeClip.compute(range, min, max, sectionOrigin, clientDelta);
                List<PlacementMesh.CpuLayer> layers = new ArrayList<>();
                LongArrayList blockEntities = new LongArrayList();

                boolean hasSchematicBlocks = view.loadSection(sx, sy, sz);
                boolean hasBlocks = view.hasUnclippedBlocks();
                boolean clippedAway = clip != null && clip.isEmpty();
                boolean clientEmpty = ClientRegions.isSectionEmpty(clientLevel, min, max, key, clientDelta);
                // 投影这里全是空气时只可能出现 overlay 的"多余方块"，客户端对应区域也为空就跳过
                boolean scan = !clippedAway && (hasSchematicBlocks || (overlay != null && !clientEmpty));

                if (scan) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                if (clipAxis != null) {
                                    int v = clipAxis.choose(x, y, z);
                                    if (v < clipMin || v > clipMax) continue;
                                }

                                pos.set(x, y, z);
                                BlockState state = view.getBlockState(pos);
                                clientPos.setWithOffset(pos, clientDelta);
                                BlockState clientState = clientLevel.getBlockState(clientPos);
                                boolean schematicAir = state.isAir();
                                boolean clientAir = clientState.isAir();

                                if (schematicAir && (clientAir || !accessor.isInAnyRegion(x, y, z))) continue;

                                boolean missing = false;
                                if (!schematicAir && (clientAir || (renderColliding && clientState != state))) {
                                    tessellate(view, state, pos, blockRenderer, fluidRenderer, blockOutput, fluidOutput, renderFluids);
                                    if (state.hasBlockEntity()) {
                                        blockEntities.add(BlockPos.asLong(x, y, z));
                                    }
                                    missing = clientAir && state.getRenderShape() == RenderShape.MODEL;
                                }

                                if (overlay != null) {
                                    try {
                                        overlay.addOverlay(pos, state, clientState, missing);
                                    } catch (Throwable e) {
                                        LitematicaFastMove.LOGGER.debug("Failed to build overlay at {}", pos.toShortString(), e);
                                    }
                                }
                            }
                        }
                    }

                    for (Map.Entry<ChunkSectionLayer, BufferBuilder> entry : builders.entrySet()) {
                        MeshData meshData = entry.getValue().build();
                        if (meshData == null) continue;

                        ByteBuffer indices = null;
                        MeshData.SortState sortState = null;
                        if (entry.getKey().translucent()) {
                            sortState = meshData.sortQuads(sortAllocator, VertexSorting.byDistance(
                                    (float) (camera.x - sectionOrigin.getX() - clientDelta.getX()),
                                    (float) (camera.y - sectionOrigin.getY() - clientDelta.getY()),
                                    (float) (camera.z - sectionOrigin.getZ() - clientDelta.getZ())));
                            if (sortState != null && meshData.indexBuffer() != null) {
                                indices = copy(Objects.requireNonNull(meshData.indexBuffer()));
                            } else {
                                sortState = null;
                            }
                        }

                        layers.add(new PlacementMesh.CpuLayer(entry.getKey(), null, copy(meshData.vertexBuffer()),
                                meshData.drawState().indexCount(), indices, sortState != null ? sortState.indexType() : null, sortState));
                        meshData.close();
                    }
                    for (Map.Entry<OverlayRenderType, BufferBuilder> entry : overlayBuilders.entrySet()) {
                        MeshData meshData = entry.getValue().build();
                        if (meshData != null) {
                            layers.add(new PlacementMesh.CpuLayer(null, entry.getKey(), copy(meshData.vertexBuffer()),
                                    meshData.drawState().indexCount(), null, null, null));
                            meshData.close();
                        }
                    }
                    builders.clear();
                    overlayBuilders.clear();
                    allocators.values().forEach(ByteBufferBuilder::clear);
                    overlayAllocators.values().forEach(ByteBufferBuilder::clear);
                    sortAllocator.clear();
                }

                // 局部构建时空区段也要返回，用于清除旧网格；有方块但被裁空的区段保留记录
                if (hasBlocks || !layers.isEmpty() || keys != null) {
                    sections.add(new PlacementMesh.CpuSection(key, sectionOrigin, hasBlocks, clientEmpty, clip, layers, blockEntities.toLongArray()));
                }
            }
        } finally {
            blockRenderer.disableCache();
            allocators.values().forEach(ByteBufferBuilder::close);
            overlayAllocators.values().forEach(ByteBufferBuilder::close);
            sortAllocator.close();
        }

        return sections;
    }

    private static void tessellate(DetachedBlockView view, BlockState state, BlockPos pos,
                                   BlockModelRendererSchematic blockRenderer, FluidModelRendererSchematic fluidRenderer,
                                   IBlockOutputSchematic blockOutput, FluidRenderer.Output fluidOutput, boolean renderFluids) {
        try {
            FluidState fluidState = state.getFluidState();
            if (renderFluids && !fluidState.isEmpty()) {
                fluidRenderer.tesselate(view, pos, fluidOutput, state, fluidState);
            }

            if (state.getRenderShape() == RenderShape.MODEL) {
                BlockStateModel model = BlockModelCacheSchematic.INSTANCE.fetchBlockStateModel(state);
                if (model != null) {
                    Vec3 offset = new Vec3(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    blockRenderer.tessellateBlock(view, state, pos, offset, model, state.getSeed(pos), blockOutput);
                }
            }
        } catch (Throwable e) {
            LitematicaFastMove.LOGGER.debug("Failed to tessellate {} at {}", state, pos.toShortString(), e);
        }
    }

    private static BufferBuilder builder(EnumMap<ChunkSectionLayer, BufferBuilder> builders,
                                         EnumMap<ChunkSectionLayer, ByteBufferBuilder> allocators, ChunkSectionLayer layer) {
        return builders.computeIfAbsent(layer, l -> new BufferBuilder(
                allocators.computeIfAbsent(l, k -> new ByteBufferBuilder(k.bufferSize())),
                PrimitiveTopology.QUADS, l.vertexFormat()));
    }

    private static ByteBuffer copy(ByteBuffer source) {
        ByteBuffer copy = MemoryUtil.memAlloc(source.remaining());
        copy.put(source.duplicate());
        copy.flip();
        return copy;
    }
}
