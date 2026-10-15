package ml.mypals.litematicafastmove.mesh;

import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.BlockModelCacheSchematic;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.mixin.BlockModelCacheSchematicAccessor;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlacementMeshManager {
    private static final class MeshBuilderThread extends Thread {
        MeshBuilderThread(Runnable r) {
            super(r, "LFMMeshBuilder");
            this.setDaemon(true);
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    private static final ExecutorService BUILDER = Executors.newFixedThreadPool(
            Math.max(1, FastMoveConfig.MESH_BUILDER_THREADS), MeshBuilderThread::new);
    private static final ExecutorService SORTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LFMTranslucentSorter");
        t.setDaemon(true);
        return t;
    });
    private static final class UploadBudget {
        long bytes = FastMoveConfig.UPLOAD_BYTES_PER_FRAME;
        final long deadline = System.nanoTime() + (long) (FastMoveConfig.UPLOAD_MILLIS_PER_FRAME * 1_000_000L);
        boolean usedAny;

        boolean hasRemaining() {
            return !this.usedAny || (this.bytes > 0 && System.nanoTime() < this.deadline);
        }
    }

    private record Completed(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer) {}
    private record Resorted(PlacementMesh.GpuLayer layer, ByteBuffer indices, double x, double y, double z) {}
    // 层范围快照 (世界坐标)，null = 不限制
    private record RangeSnapshot(Direction.Axis axis, int min, int max) {}
    private record TranslucentDraw(double distanceSq, PlacementMesh.GpuLayer layer, Vector3f offset) {}

    private static final Map<SchematicPlacement, PlacementMesh> MESHES = new ConcurrentHashMap<>();
    private static final Queue<Completed> COMPLETED = new ConcurrentLinkedQueue<>();
    private static final Queue<PlacementMesh> TO_CLOSE = new ConcurrentLinkedQueue<>();
    private static final Queue<Resorted> RESORTED = new ConcurrentLinkedQueue<>();
    private static final Set<Long> DIRTY_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static volatile Frustum frustum;
    private static int dirtySuppression;

    public static boolean isDetached(SchematicPlacement placement) {
        return FastMoveConfig.isRenderTakenOver(placement);
    }

    public static boolean isBuilderThread() {
        return Thread.currentThread() instanceof MeshBuilderThread;
    }

    public static void captureFrustum(Frustum value) {
        frustum = value;
    }

    public static void markChunkDirty(int chunkX, int chunkZ) {
        if (!MESHES.isEmpty() && dirtySuppression == 0) {
            DIRTY_CHUNKS.add(ChunkPos.pack(chunkX, chunkZ));
        }
    }

    // 层范围变化时 Litematica 会把范围内所有区块标记重绘；独立网格自己按区段裁剪，忽略这些通知
    public static void pushDirtySuppression() {
        dirtySuppression++;
    }

    public static void popDirtySuppression() {
        dirtySuppression = Math.max(0, dirtySuppression - 1);
    }

    public static void requestBuild(SchematicPlacement placement) {
        if (placement == null) return;
        if (!FastMoveConfig.ENABLE_FAST_MOVE || !isDetached(placement)) {
            dispose(placement);
            return;
        }

        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (world == null || clientLevel == null) return; // 由 tick() 稍后重试

        PlacementMesh mesh = MESHES.computeIfAbsent(placement, p -> new PlacementMesh());
        int generation = GENERATION.incrementAndGet();
        PlacementBlockAccessor accessor = new PlacementBlockAccessor(placement);
        BlockPos buildOrigin = placement.getOrigin();

        mesh.targetAccessor = accessor;
        mesh.targetBuildOrigin = buildOrigin;
        mesh.requestedGeneration = generation;

        submit(placement, mesh, generation, accessor, buildOrigin, null, BlockPos.ZERO, world, clientLevel);
    }

    private static void requestPartialBuild(SchematicPlacement placement, PlacementMesh mesh, Set<Long> keys) {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        ClientLevel clientLevel = Minecraft.getInstance().level;
        PlacementBlockAccessor accessor = mesh.targetAccessor;
        BlockPos buildOrigin = mesh.targetBuildOrigin;
        if (world == null || clientLevel == null || accessor == null || buildOrigin == null) return;

        BlockPos clientDelta = placement.getOrigin().subtract(buildOrigin);
        submit(placement, mesh, mesh.requestedGeneration, accessor, buildOrigin, keys, clientDelta, world, clientLevel);
    }

    private static void submit(SchematicPlacement placement, PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                               BlockPos buildOrigin, Set<Long> keys, BlockPos clientDelta, WorldSchematic world, ClientLevel clientLevel) {
        RangeSnapshot range = snapshotRange();
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
                    COMPLETED.add(new Completed(mesh, new PlacementMesh.BuiltPlacementBuffer(generation, buildOrigin, keys, sections)));
                }
            } catch (Throwable e) {
                LitematicaFastMove.LOGGER.error("Failed to build mesh for placement '{}'", placement.getName(), e);
            }
        });
    }

    public static void rebuildAll() {
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            requestBuild(placement);
        }
    }

    public static void rebuildAllOf(LitematicaSchematic schematic) {
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            if (placement.getSchematic() == schematic) {
                requestBuild(placement);
            }
        }
    }

    public static void dispose(SchematicPlacement placement) {
        PlacementMesh mesh = MESHES.remove(placement);
        MoveInterpolator.forget(placement);
        if (mesh != null) {
            mesh.requestedGeneration = -1;
            TO_CLOSE.add(mesh);
        }
    }

    public static void disposeAll() {
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            dispose(placement);
        }
    }

    public static void tick() {
        var manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return;

        Collection<SchematicPlacement> all = manager.getAllSchematicsPlacements();
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            if (!all.contains(placement)) {
                dispose(placement);
            }
        }

        if (!FastMoveConfig.ENABLE_FAST_MOVE) {
            disposeAll();
            return;
        }

        for (SchematicPlacement placement : all) {
            boolean detached = isDetached(placement);
            boolean hasMesh = MESHES.containsKey(placement);
            if (detached && !hasMesh) {
                requestBuild(placement);
            } else if (!detached && hasMesh) {
                dispose(placement);
            }
        }
    }

    public static void processDirtyChunks() {
        if (DIRTY_CHUNKS.isEmpty()) return;

        List<Long> dirty = new ArrayList<>(DIRTY_CHUNKS);
        dirty.forEach(DIRTY_CHUNKS::remove);

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            PlacementBlockAccessor accessor = mesh.targetAccessor;
            BlockPos buildOrigin = mesh.targetBuildOrigin;
            if (accessor == null || buildOrigin == null) continue;

            BlockPos delta = placement.getOrigin().subtract(buildOrigin);
            Set<Long> keys = getKeys(accessor, dirty, delta);
            skipStillEmptySections(mesh, accessor, keys, delta);

            if (!keys.isEmpty()) {
                requestPartialBuild(placement, mesh, keys);
            }
        }
    }

    private static void skipStillEmptySections(PlacementMesh mesh, PlacementBlockAccessor accessor, Set<Long> keys, BlockPos delta) {
        if (keys.isEmpty() || mesh.uploadingFull != null || !mesh.deferredPartials.isEmpty() || !mesh.uploadingPartials.isEmpty() ||
            mesh.activeBuildOrigin == null || !mesh.activeBuildOrigin.equals(mesh.targetBuildOrigin)) return;

        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) return;

        keys.removeIf(key -> {
            PlacementMesh.GpuSection section = mesh.active.get(key);
            // 不在 active 中的区段既没有投影方块也没有网格，只有真实世界出现方块 (多余方块 overlay) 时才需要构建
            boolean wasEmpty = section == null || section.clientEmpty();
            return wasEmpty && isSectionClientRegionEmpty(clientLevel, accessor.minPos, accessor.maxPos, key, delta);
        });
    }

    private static boolean isSectionClientRegionEmpty(ClientLevel level, BlockPos min, BlockPos max, long key, BlockPos delta) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        int x0 = Math.max(sx << 4, min.getX()) - 1, x1 = Math.min((sx << 4) + 15, max.getX()) + 1;
        int y0 = Math.max(sy << 4, min.getY()) - 1, y1 = Math.min((sy << 4) + 15, max.getY()) + 1;
        int z0 = Math.max(sz << 4, min.getZ()) - 1, z1 = Math.min((sz << 4) + 15, max.getZ()) + 1;
        if (x0 > x1 || y0 > y1 || z0 > z1) return true;
        return isClientRegionEmpty(level, x0 + delta.getX(), y0 + delta.getY(), z0 + delta.getZ(),
                x1 + delta.getX(), y1 + delta.getY(), z1 + delta.getZ());
    }

    private static @NonNull Set<Long> getKeys(PlacementBlockAccessor accessor, List<Long> dirty, BlockPos delta) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        Set<Long> keys = new HashSet<>();

        for (long packed : dirty) {
            ChunkPos chunk = ChunkPos.unpack(packed);
            int bx0 = Math.max((chunk.x() << 4) - delta.getX(), min.getX());
            int bx1 = Math.min((chunk.x() << 4) + 15 - delta.getX(), max.getX());
            int bz0 = Math.max((chunk.z() << 4) - delta.getZ(), min.getZ());
            int bz1 = Math.min((chunk.z() << 4) + 15 - delta.getZ(), max.getZ());
            if (bx0 > bx1 || bz0 > bz1) continue;

            for (int sx = bx0 >> 4; sx <= bx1 >> 4; sx++) {
                for (int sz = bz0 >> 4; sz <= bz1 >> 4; sz++) {
                    for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                        keys.add(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
        return keys;
    }

    private static RangeSnapshot snapshotRange() {
        LayerRange range = DataManager.getRenderLayerRange();
        if (range == null || range.getLayerMode() == LayerMode.ALL) return null;
        return new RangeSnapshot(range.getAxis(), range.getMinLayerBoundary(), range.getMaxLayerBoundary());
    }

    /**
     * 区段 (构建坐标 sectionOrigin，世界坐标 = 构建坐标 + delta) 在层范围下需要的裁剪
     */
    private static PlacementMesh.Clip computeClip(RangeSnapshot range, BlockPos min, BlockPos max, BlockPos sectionOrigin, BlockPos delta) {
        if (range == null) return null;

        Direction.Axis axis = range.axis();
        int origin = axis.choose(sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ());
        int d = axis.choose(delta.getX(), delta.getY(), delta.getZ());
        int secMin = Math.max(origin, axis.choose(min.getX(), min.getY(), min.getZ()));
        int secMax = Math.min(origin + 15, axis.choose(max.getX(), max.getY(), max.getZ()));
        int lo = Math.max(secMin + d, range.min());
        int hi = Math.min(secMax + d, range.max());

        if (lo <= secMin + d && hi >= secMax + d) return null;
        if (lo > hi) return new PlacementMesh.Clip(axis, 1, 0);
        return new PlacementMesh.Clip(axis, lo - d - origin, hi - d - origin);
    }

    private static List<PlacementMesh.CpuSection> build(PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                                                        Set<Long> keys, BlockPos clientDelta, RangeSnapshot range, Vec3 camera,
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

        // 层范围换算到构建坐标
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
                PlacementMesh.Clip clip = computeClip(range, min, max, sectionOrigin, clientDelta);
                List<PlacementMesh.CpuLayer> layers = new ArrayList<>();

                boolean hasSchematicBlocks = view.loadSection(sx, sy, sz);
                boolean hasBlocks = view.hasUnclippedBlocks();
                boolean clippedAway = clip != null && clip.isEmpty();
                boolean clientEmpty = isSectionClientRegionEmpty(clientLevel, min, max, key, clientDelta);
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

                                // 与 Litematica 相同：只显示真实世界中尚未放置 (或开启碰撞显示时不同) 的方块
                                boolean missing = false;
                                if (!schematicAir && (clientAir || (renderColliding && clientState != state))) {
                                    tessellate(view, state, pos, blockRenderer, fluidRenderer, blockOutput, fluidOutput, renderFluids);
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
                            // 半透明层按构建时的相机位置预排序，之后由渲染线程按相机移动重新排序
                            sortState = meshData.sortQuads(sortAllocator, VertexSorting.byDistance(
                                    (float) (camera.x - sectionOrigin.getX() - clientDelta.getX()),
                                    (float) (camera.y - sectionOrigin.getY() - clientDelta.getY()),
                                    (float) (camera.z - sectionOrigin.getZ() - clientDelta.getZ())));
                            if (sortState != null && meshData.indexBuffer() != null) {
                                indices = copy(meshData.indexBuffer());
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
                    sections.add(new PlacementMesh.CpuSection(key, sectionOrigin, hasBlocks, clientEmpty, clip, layers));
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

    private static boolean isClientRegionEmpty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;

                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy);
                    if (index >= 0 && index < chunk.getSectionsCount() && !chunk.getSection(index).hasOnlyAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static ByteBuffer copy(ByteBuffer source) {
        ByteBuffer copy = MemoryUtil.memAlloc(source.remaining());
        copy.put(source.duplicate());
        copy.flip();
        return copy;
    }

    private static void processQueues() {
        PlacementMesh closing;
        while ((closing = TO_CLOSE.poll()) != null) {
            closing.close();
        }

        Completed completed;
        while ((completed = COMPLETED.poll()) != null) {
            PlacementMesh mesh = completed.mesh();
            PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer = completed.builtPlacementBuffer();

            if (builtPlacementBuffer.generation != mesh.requestedGeneration) {
                builtPlacementBuffer.discard();
            } else if (builtPlacementBuffer.isFull()) {
                if (mesh.uploadingFull != null) {
                    mesh.uploadingFull.discard();
                }
                mesh.uploadingFull = builtPlacementBuffer;
            } else if (mesh.uploadingFull == null && mesh.activeGeneration == builtPlacementBuffer.generation) {
                mesh.uploadingPartials.add(builtPlacementBuffer);
            } else {
                mesh.deferredPartials.add(builtPlacementBuffer);
            }
        }

        UploadBudget budget = new UploadBudget();

        for (PlacementMesh mesh : MESHES.values()) {
            if (!budget.hasRemaining()) break;

            PlacementMesh.BuiltPlacementBuffer full = mesh.uploadingFull;
            if (full != null) {
                upload(full, budget);
                if (!full.isFullyUploaded()) continue;

                LitematicaFastMove.LOGGER.debug("Detached placement mesh ready: {} sections (generation {})",
                        full.uploaded.size(), full.generation);
                mesh.active.values().forEach(PlacementMesh.GpuSection::close);
                mesh.active.clear();
                mesh.clipRebuildPending.clear();
                for (PlacementMesh.GpuSection section : full.uploaded) {
                    mesh.active.put(section.key(), section);
                }
                mesh.activeGeneration = full.generation;
                mesh.activeBuildOrigin = full.buildOrigin;
                mesh.uploadingFull = null;

                mesh.uploadingPartials.forEach(PlacementMesh.BuiltPlacementBuffer::discard);
                mesh.uploadingPartials.clear();
                for (PlacementMesh.BuiltPlacementBuffer partial : mesh.deferredPartials) {
                    if (partial.generation == mesh.activeGeneration) {
                        mesh.uploadingPartials.add(partial);
                    } else {
                        partial.discard();
                    }
                }
                mesh.deferredPartials.clear();
            }
            PlacementMesh.BuiltPlacementBuffer partial;
            while ((partial = mesh.uploadingPartials.peek()) != null && budget.hasRemaining()) {
                upload(partial, budget);
                if (!partial.isFullyUploaded()) break;
                mesh.uploadingPartials.poll();
                applyPartial(mesh, partial);
            }
        }

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

    private static void applyPartial(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer) {
        for (long key : builtPlacementBuffer.keys) {
            PlacementMesh.GpuSection old = mesh.active.remove(key);
            if (old != null) old.close();
            mesh.clipRebuildPending.remove(key);
        }
        for (PlacementMesh.GpuSection section : builtPlacementBuffer.uploaded) {
            if (section.hasBlocks() || !section.layers().isEmpty()) {
                mesh.active.put(section.key(), section);
            } else {
                section.close();
            }
        }
        builtPlacementBuffer.uploaded.clear();
    }

    private static void upload(PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer, UploadBudget budget) {
        GpuDevice device = RenderSystem.getDevice();

        while (budget.hasRemaining() && !builtPlacementBuffer.isFullyUploaded()) {
            budget.usedAny = true;
            PlacementMesh.CpuSection section = builtPlacementBuffer.pending.get(builtPlacementBuffer.nextToUpload++);
            List<PlacementMesh.GpuLayer> layers = new ArrayList<>(section.layers().size());
            for (PlacementMesh.CpuLayer layer : section.layers()) {
                budget.bytes -= layer.vertices().remaining();
                GpuBuffer vertexBuffer = device.createBuffer(() -> "litematicafastmove:placement_mesh",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, layer.vertices());
                GpuBuffer indexBuffer = null;
                PlacementMesh.SortInfo sort = null;
                if (layer.indices() != null) {
                    budget.bytes -= layer.indices().remaining();
                    indexBuffer = device.createBuffer(() -> "litematicafastmove:placement_mesh_indices",
                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, layer.indices());
                    sort = new PlacementMesh.SortInfo(layer.sortState(), Double.NaN, Double.NaN, Double.NaN);
                }
                layer.free();
                layers.add(new PlacementMesh.GpuLayer(layer.layer(), layer.overlay(), vertexBuffer, layer.indexCount(),
                        indexBuffer, layer.indexType(), sort));
            }
            builtPlacementBuffer.uploaded.add(new PlacementMesh.GpuSection(section.key(), section.origin(),
                    section.hasBlocks(), section.clientEmpty(), section.clip(), layers));
        }
    }

    private static void scheduleResort(PlacementMesh.GpuLayer layer, double x, double y, double z) {
        PlacementMesh.SortInfo sort = layer.sort();
        sort.pending = true;
        int size = layer.indexCount() * layer.indexType().bytes;
        SORTER.submit(() -> {
            ByteBuffer indices = MemoryUtil.memAlloc(size);
            try {
                sort.state.writeSortedIndexBuffer(indices, VertexSorting.byDistance((float) x, (float) y, (float) z));
                indices.position(0).limit(size);
                RESORTED.add(new Resorted(layer, indices, x, y, z));
            } catch (Throwable e) {
                MemoryUtil.memFree(indices);
                LitematicaFastMove.LOGGER.debug("Failed to resort translucent section", e);
            }
        });
    }

    private interface SectionVisitor {
        void visit(PlacementMesh.GpuSection section, double ox, double oy, double oz);
    }

    private static void forEachVisibleSection(double cameraX, double cameraY, double cameraZ, boolean requestClipRebuilds, SectionVisitor visitor) {
        RangeSnapshot range = snapshotRange();
        Frustum fr = frustum;
        int renderDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double maxDistanceSq = (double) renderDistance * renderDistance;

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            if (mesh.active.isEmpty() || !placement.isEnabled() || !placement.isRenderingEnabled()) continue;

            BlockPos delta = placement.getOrigin().subtract(mesh.activeBuildOrigin);
            Vec3 lag = MoveInterpolator.getLag(placement);
            PlacementBlockAccessor accessor = mesh.targetAccessor;
            boolean sameBuild = accessor != null && mesh.activeBuildOrigin.equals(mesh.targetBuildOrigin);

            if (fr != null && sameBuild) {
                AABB box = new AABB(
                        accessor.minPos.getX() + delta.getX() + lag.x, accessor.minPos.getY() + delta.getY() + lag.y, accessor.minPos.getZ() + delta.getZ() + lag.z,
                        accessor.maxPos.getX() + delta.getX() + lag.x + 1, accessor.maxPos.getY() + delta.getY() + lag.y + 1, accessor.maxPos.getZ() + delta.getZ() + lag.z + 1);
                if (!fr.isVisible(box)) continue;
            }

            boolean canRebuild = requestClipRebuilds && sameBuild && mesh.uploadingFull == null && PendingMoveTracker.get(placement) == null;
            Set<Long> clipKeys = null;

            for (PlacementMesh.GpuSection section : mesh.active.values()) {
                if (sameBuild) {
                    PlacementMesh.Clip required = computeClip(range, accessor.minPos, accessor.maxPos, section.origin(), delta);
                    if (required != null && required.isEmpty()) continue;
                    if (canRebuild && (section.hasBlocks() || !section.layers().isEmpty()) && !Objects.equals(required, section.clip()) &&
                        !mesh.clipRebuildPending.contains(section.key())) {
                        if (clipKeys == null) clipKeys = new HashSet<>();
                        clipKeys.add(section.key());
                    }
                }
                if (section.layers().isEmpty()) continue;

                double wx = section.origin().getX() + delta.getX() + lag.x;
                double wy = section.origin().getY() + delta.getY() + lag.y;
                double wz = section.origin().getZ() + delta.getZ() + lag.z;
                double cx = wx + 8.0 - cameraX;
                double cz = wz + 8.0 - cameraZ;
                if (cx * cx + cz * cz > maxDistanceSq) continue;
                if (fr != null && !fr.isVisible(new AABB(wx, wy, wz, wx + 16, wy + 16, wz + 16))) continue;

                visitor.visit(section, wx - cameraX, wy - cameraY, wz - cameraZ);
            }

            if (clipKeys != null) {
                mesh.clipRebuildPending.addAll(clipKeys);
                requestPartialBuild(placement, mesh, clipKeys);
            }
        }
    }

    public static boolean appendDraws(Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                      List<DynamicGpuData.Transform> transforms,
                                      Matrix4fc modelView, Vector4fc colorModulator, Matrix4fc textureMatrix,
                                      double cameraX, double cameraY, double cameraZ,
                                      LocalIntRef maxIndexCount) {
        processQueues();
        if (MESHES.isEmpty()) return false;
        MoveInterpolator.update();

        boolean[] added = {false};
        int[] resortBudget = {FastMoveConfig.TRANSLUCENT_RESORTS_PER_FRAME};
        List<TranslucentDraw> translucentDraws = new ArrayList<>();

        forEachVisibleSection(cameraX, cameraY, cameraZ, true, (section, ox, oy, oz) -> {
            Vector3f offset = new Vector3f((float) ox, (float) oy, (float) oz);

            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.layer() == null || layer.vertexBuffer().isClosed()) continue;

                if (layer.sort() != null) {
                    PlacementMesh.SortInfo sort = layer.sort();
                    double rx = -ox, ry = -oy, rz = -oz; // 相机相对区段原点
                    double dx = rx - sort.lastX, dy = ry - sort.lastY, dz = rz - sort.lastZ;
                    if (!sort.pending && resortBudget[0] > 0 &&
                        (Double.isNaN(sort.lastX) || dx * dx + dy * dy + dz * dz >
                         FastMoveConfig.TRANSLUCENT_RESORT_DISTANCE * FastMoveConfig.TRANSLUCENT_RESORT_DISTANCE)) {
                        resortBudget[0]--;
                        scheduleResort(layer, rx, ry, rz);
                    }
                    translucentDraws.add(new TranslucentDraw((ox + 8) * (ox + 8) + (oy + 8) * (oy + 8) + (oz + 8) * (oz + 8), layer, offset));
                    continue;
                }

                addDraw(renderMap, transforms, modelView, colorModulator, textureMatrix, maxIndexCount, layer, offset);
                added[0] = true;
            }
        });

        translucentDraws.sort(Comparator.comparingDouble(TranslucentDraw::distanceSq));
        for (TranslucentDraw draw : translucentDraws) {
            addDraw(renderMap, transforms, modelView, colorModulator, textureMatrix, maxIndexCount, draw.layer(), draw.offset());
            added[0] = true;
        }

        return added[0];
    }

    private static void addDraw(Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                List<DynamicGpuData.Transform> transforms,
                                Matrix4fc modelView, Vector4fc colorModulator, Matrix4fc textureMatrix,
                                LocalIntRef maxIndexCount, PlacementMesh.GpuLayer layer, Vector3f offset) {
        final int slot = transforms.size();
        transforms.add(new DynamicGpuData.Transform(modelView, colorModulator, offset, textureMatrix));
        renderMap.get(layer.layer()).add(new RenderPass.Draw<>(
                0, layer.vertexBuffer(), layer.indexBuffer(), layer.indexType(), 0, layer.indexCount(), 0,
                (slices, uploader) -> uploader.setUniform("DynamicTransforms", slices[slot])));

        if (layer.indexBuffer() == null && layer.indexCount() > maxIndexCount.get()) {
            maxIndexCount.set(layer.indexCount());
        }
    }

    /**
     * 同一种 overlay 的所有区段合并为一个 RenderPass 绘制
     */
    public static void drawOverlays(OverlayRenderType type, Camera camera) {
        if (MESHES.isEmpty()) return;

        Vec3 cameraPos = camera.position();
        Matrix4f viewMatrix = RenderSystem.getModelViewMatrixCopy();
        Vector4f colorModulator = new Vector4f(1f, 1f, 1f, 1f);
        Matrix4f textureMatrix = new Matrix4f();
        List<RenderPass.Draw<GpuBufferSlice[]>> draws = new ArrayList<>();
        List<DynamicGpuData.Transform> transforms = new ArrayList<>();
        int[] maxIndexCount = {0};

        forEachVisibleSection(cameraPos.x, cameraPos.y, cameraPos.z, false, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.overlay() != type || layer.indexCount() <= 0 || layer.vertexBuffer().isClosed()) continue;

                final int slot = transforms.size();
                Matrix4f modelView = new Matrix4f(viewMatrix).translate((float) ox, (float) oy, (float) oz);
                transforms.add(new DynamicGpuData.Transform(modelView, colorModulator, new Vector3f(), textureMatrix));
                draws.add(new RenderPass.Draw<>(0, layer.vertexBuffer(), null, null, 0, layer.indexCount(), 0,
                        (slices, uploader) -> uploader.setUniform("DynamicTransforms", slices[slot])));
                maxIndexCount[0] = Math.max(maxIndexCount[0], layer.indexCount());
            }
        });

        if (draws.isEmpty()) return;

        boolean renderThrough = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue() ||
                                Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld();
        RenderPipeline pipeline = renderThrough ? type.renderThrough() : type.pipeline();
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = target.getColorTextureView();
        GpuTextureView depthTexture = target.getDepthTextureView();
        RenderSystem.AutoStorageIndexBuffer sequentialIndices = RenderSystem.getSequentialBuffer(type.topology());
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms.toArray(new DynamicGpuData.Transform[0]));

        assert colorTexture != null;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "litematicafastmove:placement_overlay", colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.drawMultipleIndexed(draws, sequentialIndices.getBuffer(maxIndexCount[0]), sequentialIndices.type(),
                    List.of("DynamicTransforms"), slices);
        }
    }
}
