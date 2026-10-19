package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.config.ThreadPools;
import ml.mypals.litematicafastmove.render.IrisFormat;
import ml.mypals.litematicafastmove.render.SchematicExit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /**
     * 提交构建；keys == null 为完整构建。区段按离相机由近到远分批，分散到所有构建线程并行处理：
     * 局部构建每批完成即交给 onBuilt，逐批生效；完整构建等所有批次完成后合并为一次整体替换。
     * 被新一代构建取代时直接丢弃
     */
    static void submit(SchematicPlacement placement, PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                       BlockPos buildOrigin, Set<Long> keys, BlockPos clientDelta, WorldSchematic world, ClientLevel clientLevel,
                       Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt) {
        LayerRangeClip.Range range = LayerRangeClip.snapshot();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        boolean full = keys == null;

        List<Long> sectionKeys = full ? allSections(accessor) : new ArrayList<>(keys);
        sectionKeys.sort(Comparator.comparingDouble(key -> distanceSq(key, clientDelta, camera)));
        List<List<Long>> batches = partition(sectionKeys, Math.max(1, FastMoveConfig.BUILD_BATCH_SECTIONS.getIntegerValue()));
        FullBuild collector = full ? new FullBuild(placement, generation, buildOrigin, batches.size(), onBuilt) : null;

        for (List<Long> batch : batches) {
            BUILDER.submit(() -> {
                List<PlacementMesh.CpuSection> sections = null;
                try {
                    List<List<PlacementMesh.CpuSection>> built = new ArrayList<>(1);
                    SchematicExit.plainFormat(() -> IrisFormat.plainBuild(() -> built.add(
                            build(mesh, generation, accessor, batch, !full,
                                    clientDelta, range, camera, world, clientLevel))));
                    sections = built.getFirst();
                } catch (Throwable e) {
                    LitematicaFastMove.LOGGER.error("Failed to build mesh for placement '{}'", placement.getName(), e);
                }
                if (full) {
                    collector.complete(sections);
                } else if (sections != null) {
                    onBuilt.accept(new PlacementMesh.BuiltPlacementBuffer(generation, buildOrigin, new HashSet<>(batch), sections));
                }
            });
        }
    }

    private static final class FullBuild {
        private final SchematicPlacement placement;
        private final int generation;
        private final BlockPos buildOrigin;
        private final Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt;
        private final List<PlacementMesh.CpuSection> sections = new ArrayList<>();
        private final long start = System.nanoTime();
        private int remaining;
        private boolean failed;

        FullBuild(SchematicPlacement placement, int generation, BlockPos buildOrigin, int batches, Consumer<PlacementMesh.BuiltPlacementBuffer> onBuilt) {
            this.placement = placement;
            this.generation = generation;
            this.buildOrigin = buildOrigin;
            this.remaining = batches;
            this.onBuilt = onBuilt;
        }

        void complete(List<PlacementMesh.CpuSection> batch) {
            synchronized (this) {
                if (batch == null) {
                    this.failed = true;
                } else {
                    this.sections.addAll(batch);
                }
                if (--this.remaining > 0) return;
            }

            if (this.failed) {
                this.sections.forEach(section -> section.layers().forEach(PlacementMesh.CpuLayer::free));
                return;
            }
            LitematicaFastMove.LOGGER.debug("Built detached mesh for '{}' in {} ms ({} sections)",
                    this.placement.getName(), (System.nanoTime() - this.start) / 1_000_000, this.sections.size());
            this.onBuilt.accept(new PlacementMesh.BuiltPlacementBuffer(this.generation, this.buildOrigin, null, this.sections));
        }
    }

    private static List<Long> allSections(PlacementBlockAccessor accessor) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        List<Long> keys = new ArrayList<>();
        for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
            for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
                for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
                    keys.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
        return keys;
    }

    private static double distanceSq(long key, BlockPos delta, Vec3 camera) {
        double dx = (SectionPos.x(key) << 4) + 8 + delta.getX() - camera.x;
        double dy = (SectionPos.y(key) << 4) + 8 + delta.getY() - camera.y;
        double dz = (SectionPos.z(key) << 4) + 8 + delta.getZ() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<List<Long>> partition(List<Long> keys, int size) {
        List<List<Long>> batches = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += size) {
            batches.add(new ArrayList<>(keys.subList(i, Math.min(keys.size(), i + size))));
        }
        return batches;
    }

    // emitEmpty：局部构建时空区段也要返回，用于清除旧网格
    private static List<PlacementMesh.CpuSection> build(PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                                                        List<Long> sectionKeys, boolean emitEmpty, BlockPos clientDelta,
                                                        LayerRangeClip.Range range, Vec3 camera,
                                                        WorldSchematic world, ClientLevel clientLevel) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

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

        // Litematica 0.26（1.21.11）：方块用它的 BlockModelRendererSchematic 画（用它的 AO，面剔除由
        // MixinBlockModelRendererSchematic 裁到投影范围），流体用原版的流体渲染
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        IWorldSchematicRenderer worldRenderer = LitematicaRenderer.getInstance().getWorldRenderer();
        BlockModelRendererSchematic blockRenderer = new BlockModelRendererSchematic(mc.getBlockColors(), dispatcher);
        BlockModelRendererSchematic.enableCache();
        RandomSource random = RandomSource.create();
        PoseStack matrices = new PoseStack();

        boolean translucent = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        boolean renderFluids = Configs.Visuals.ENABLE_SCHEMATIC_FLUIDS.getBooleanValue();
        boolean renderColliding = Configs.Visuals.RENDER_COLLIDING_SCHEMATIC_BLOCKS.getBooleanValue();

        Function<ChunkSectionLayer, BufferBuilder> fluidOutput = layer -> builder(builders, allocators, layer);
        Function<ChunkSectionLayer, BufferBuilder> blockOutput = layer ->
                builder(builders, allocators, translucent ? ChunkSectionLayer.TRANSLUCENT : layer);

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
                                t.getDrawMode(), t.getVertexFormat())))
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
                                    tessellate(view, state, pos, dispatcher, worldRenderer, blockRenderer, random, matrices,
                                            blockOutput, fluidOutput, renderFluids);
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
                        if (entry.getKey() == ChunkSectionLayer.TRANSLUCENT) {
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

                        ByteBuffer vertices = copy(meshData.vertexBuffer());
                        layers.add(new PlacementMesh.CpuLayer(entry.getKey(), null, vertices,
                                meshData.drawState().indexCount(), indices, sortState != null ? sortState.indexType() : null, sortState, hash(vertices)));
                        meshData.close();
                    }
                    for (Map.Entry<OverlayRenderType, BufferBuilder> entry : overlayBuilders.entrySet()) {
                        MeshData meshData = entry.getValue().build();
                        if (meshData != null) {
                            ByteBuffer vertices = copy(meshData.vertexBuffer());
                            layers.add(new PlacementMesh.CpuLayer(null, entry.getKey(), vertices,
                                    meshData.drawState().indexCount(), null, null, null, hash(vertices)));
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
                if (hasBlocks || !layers.isEmpty() || emitEmpty) {
                    sections.add(new PlacementMesh.CpuSection(key, sectionOrigin, hasBlocks, clientEmpty, clip, layers, blockEntities.toLongArray()));
                }
            }
        } finally {
            BlockModelRendererSchematic.disableCache();
            allocators.values().forEach(ByteBufferBuilder::close);
            overlayAllocators.values().forEach(ByteBufferBuilder::close);
            sortAllocator.close();
        }

        return sections;
    }

    /** 和 Litematica 0.26 的 ChunkRendererSchematicVbo 一样，画进本区段的缓冲（顶点用区段内坐标） */
    private static void tessellate(DetachedBlockView view, BlockState state, BlockPos pos, BlockRenderDispatcher dispatcher,
                                   IWorldSchematicRenderer worldRenderer, BlockModelRendererSchematic blockRenderer,
                                   RandomSource random, PoseStack matrices, Function<ChunkSectionLayer, BufferBuilder> blockOutput,
                                   Function<ChunkSectionLayer, BufferBuilder> fluidOutput, boolean renderFluids) {
        try {
            FluidState fluidState = state.getFluidState();
            if (renderFluids && !fluidState.isEmpty()) {
                // 原版流体渲染本来就写区段内坐标
                dispatcher.renderLiquid(pos, view, fluidOutput.apply(ItemBlockRenderTypes.getRenderLayer(fluidState)), state, fluidState);
            }

            if (state.getRenderShape() == RenderShape.MODEL) {
                random.setSeed(state.getSeed(pos));
                List<BlockModelPart> parts = worldRenderer.getModelParts(pos, state, random);
                matrices.pushPose();
                matrices.translate(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                blockRenderer.renderModel(view, parts, state, pos, matrices,
                        blockOutput.apply(ItemBlockRenderTypes.getChunkRenderType(state)), false, OverlayTexture.NO_OVERLAY);
                matrices.popPose();
            }
        } catch (Throwable e) {
            LitematicaFastMove.LOGGER.debug("Failed to tessellate {} at {}", state, pos.toShortString(), e);
        }
    }

    private static BufferBuilder builder(EnumMap<ChunkSectionLayer, BufferBuilder> builders,
                                         EnumMap<ChunkSectionLayer, ByteBufferBuilder> allocators, ChunkSectionLayer layer) {
        return builders.computeIfAbsent(layer, l -> new BufferBuilder(
                allocators.computeIfAbsent(l, k -> new ByteBufferBuilder(k.bufferSize())),
                // 直接用原版方块格式：不在渲染线程时，原版管线报出的格式可能已被 Iris 扩展（见 IrisFormat）
                VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK));
    }

    private static ByteBuffer copy(ByteBuffer source) {
        ByteBuffer copy = MemoryUtil.memAlloc(source.remaining());
        copy.put(source.duplicate());
        copy.flip();
        return copy;
    }

    // 64 位内容哈希，用于判断重建后的网格是否与旧网格相同
    private static long hash(ByteBuffer buffer) {
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int end = b.limit();
        long h = 0x9E3779B97F4A7C15L ^ (b.remaining() * 0xC2B2AE3D27D4EB4FL);
        int i = b.position();
        for (; i + 8 <= end; i += 8) {
            h ^= Long.rotateLeft(b.getLong(i) * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B97F4A7C15L;
            h = Long.rotateLeft(h, 27) * 0x9E3779B97F4A7C15L + 0x85EBCA77C2B2AE63L;
        }
        for (; i < end; i++) {
            h ^= (b.get(i) & 0xFFL) * 0x27D4EB2F165667C5L;
            h = Long.rotateLeft(h, 11) * 0x9E3779B97F4A7C15L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }
}
