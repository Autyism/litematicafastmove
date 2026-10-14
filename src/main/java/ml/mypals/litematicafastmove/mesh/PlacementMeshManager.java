package ml.mypals.litematicafastmove.mesh;

import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
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
import ml.mypals.litematicafastmove.LitematicaFastMove;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.mixin.BlockModelCacheSchematicAccessor;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final ExecutorService BUILDER = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
            r -> {
                Thread t = new Thread(r, "LFMMeshBuilder");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
    );
    private static final long UPLOAD_BUDGET_BYTES = 24L * 1024 * 1024;

    private record Completed(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer) {}

    private static final Map<SchematicPlacement, PlacementMesh> MESHES = new ConcurrentHashMap<>();
    private static final Queue<Completed> COMPLETED = new ConcurrentLinkedQueue<>();
    private static final Queue<PlacementMesh> TO_CLOSE = new ConcurrentLinkedQueue<>();
    private static final Set<Long> DIRTY_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();

    public static boolean isDetached(SchematicPlacement placement) {
        return FastMoveConfig.isPlacementDetached(placement);
    }

    public static void markChunkDirty(int chunkX, int chunkZ) {
        if (!MESHES.isEmpty()) {
            DIRTY_CHUNKS.add(ChunkPos.pack(chunkX, chunkZ));
        }
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
        BUILDER.submit(() -> {
            try {
                long start = System.nanoTime();
                List<PlacementMesh.CpuSection> sections = build(mesh, generation, accessor, keys, clientDelta, world, clientLevel);
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

            if (!keys.isEmpty()) {
                requestPartialBuild(placement, mesh, keys);
            }
        }
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

    private static List<PlacementMesh.CpuSection> build(PlacementMesh mesh, int generation, PlacementBlockAccessor accessor,
                                                        Set<Long> keys, BlockPos clientDelta,
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
                int x0 = Math.max(sx << 4, min.getX()), x1 = Math.min((sx << 4) + 15, max.getX());
                int y0 = Math.max(sy << 4, min.getY()), y1 = Math.min((sy << 4) + 15, max.getY());
                int z0 = Math.max(sz << 4, min.getZ()), z1 = Math.min((sz << 4) + 15, max.getZ());
                List<PlacementMesh.CpuLayer> layers = new ArrayList<>();

                boolean hasSchematicBlocks = view.loadSection(sx, sy, sz);
                // 投影这里全是空气时只可能出现 overlay 的"多余方块"，客户端对应区域也为空就跳过
                boolean scan = hasSchematicBlocks || (overlay != null && x0 <= x1 && y0 <= y1 && z0 <= z1 &&
                        !isClientRegionEmpty(clientLevel, x0 + clientDelta.getX(), y0 + clientDelta.getY(), z0 + clientDelta.getZ(),
                                x1 + clientDelta.getX(), y1 + clientDelta.getY(), z1 + clientDelta.getZ()));

                if (scan) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
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
                        if (meshData != null) {
                            layers.add(new PlacementMesh.CpuLayer(entry.getKey(), null, copy(meshData.vertexBuffer()), meshData.drawState().indexCount()));
                            meshData.close();
                        }
                    }
                    for (Map.Entry<OverlayRenderType, BufferBuilder> entry : overlayBuilders.entrySet()) {
                        MeshData meshData = entry.getValue().build();
                        if (meshData != null) {
                            layers.add(new PlacementMesh.CpuLayer(null, entry.getKey(), copy(meshData.vertexBuffer()), meshData.drawState().indexCount()));
                            meshData.close();
                        }
                    }
                    builders.clear();
                    overlayBuilders.clear();
                    allocators.values().forEach(ByteBufferBuilder::clear);
                    overlayAllocators.values().forEach(ByteBufferBuilder::clear);
                }

                // 局部构建时空区段也要返回，用于清除旧网格
                if (!layers.isEmpty() || keys != null) {
                    sections.add(new PlacementMesh.CpuSection(key, new BlockPos(sx << 4, sy << 4, sz << 4), layers));
                }
            }
        } finally {
            blockRenderer.disableCache();
            allocators.values().forEach(ByteBufferBuilder::close);
            overlayAllocators.values().forEach(ByteBufferBuilder::close);
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
                applyPartial(mesh, builtPlacementBuffer);
            } else {
                mesh.deferredPartials.add(builtPlacementBuffer);
            }
        }

        long budget = UPLOAD_BUDGET_BYTES;

        for (PlacementMesh mesh : MESHES.values()) {
            PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer = mesh.uploadingFull;
            if (builtPlacementBuffer == null) continue;

            budget = upload(builtPlacementBuffer, budget);

            if (builtPlacementBuffer.isFullyUploaded()) {
                LitematicaFastMove.LOGGER.debug("Detached placement mesh ready: {} sections (generation {})",
                        builtPlacementBuffer.uploaded.size(), builtPlacementBuffer.generation);
                mesh.active.values().forEach(PlacementMesh.GpuSection::close);
                mesh.active.clear();
                for (PlacementMesh.GpuSection section : builtPlacementBuffer.uploaded) {
                    mesh.active.put(section.key(), section);
                }
                mesh.activeGeneration = builtPlacementBuffer.generation;
                mesh.activeBuildOrigin = builtPlacementBuffer.buildOrigin;
                mesh.uploadingFull = null;

                for (PlacementMesh.BuiltPlacementBuffer partial : mesh.deferredPartials) {
                    if (partial.generation == mesh.activeGeneration) {
                        applyPartial(mesh, partial);
                    } else {
                        partial.discard();
                    }
                }
                mesh.deferredPartials.clear();
            }
        }
    }

    private static void applyPartial(PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer) {
        upload(builtPlacementBuffer, Long.MAX_VALUE);
        for (long key : builtPlacementBuffer.keys) {
            PlacementMesh.GpuSection old = mesh.active.remove(key);
            if (old != null) old.close();
        }
        for (PlacementMesh.GpuSection section : builtPlacementBuffer.uploaded) {
            if (!section.layers().isEmpty()) {
                mesh.active.put(section.key(), section);
            }
        }
        builtPlacementBuffer.uploaded.clear();
    }

    private static long upload(PlacementMesh.BuiltPlacementBuffer builtPlacementBuffer, long budget) {
        GpuDevice device = RenderSystem.getDevice();

        while (budget > 0 && !builtPlacementBuffer.isFullyUploaded()) {
            PlacementMesh.CpuSection section = builtPlacementBuffer.pending.get(builtPlacementBuffer.nextToUpload++);
            List<PlacementMesh.GpuLayer> layers = new ArrayList<>(section.layers().size());
            for (PlacementMesh.CpuLayer layer : section.layers()) {
                budget -= layer.vertices().remaining();
                GpuBuffer buffer = device.createBuffer(() -> "litematicafastmove:placement_mesh",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, layer.vertices());
                layer.free();
                layers.add(new PlacementMesh.GpuLayer(layer.layer(), layer.overlay(), buffer, layer.indexCount()));
            }
            builtPlacementBuffer.uploaded.add(new PlacementMesh.GpuSection(section.key(), section.origin(), layers));
        }

        return budget;
    }

    public static boolean appendDraws(Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                      List<DynamicGpuData.Transform> transforms,
                                      Matrix4fc modelView, Vector4fc colorModulator, Matrix4fc textureMatrix,
                                      double cameraX, double cameraY, double cameraZ,
                                      LocalIntRef maxIndexCount) {
        processQueues();
        if (MESHES.isEmpty()) return false;
        MoveInterpolator.update();

        int renderDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double maxDistanceSq = (double) renderDistance * renderDistance;
        boolean added = false;

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            if (mesh.active.isEmpty() || !placement.isEnabled() || !placement.isRenderingEnabled()) continue;

            BlockPos delta = placement.getOrigin().subtract(mesh.activeBuildOrigin);
            Vec3 lag = MoveInterpolator.getLag(placement);

            for (PlacementMesh.GpuSection section : mesh.active.values()) {
                double ox = section.origin().getX() + delta.getX() + lag.x - cameraX;
                double oy = section.origin().getY() + delta.getY() + lag.y - cameraY;
                double oz = section.origin().getZ() + delta.getZ() + lag.z - cameraZ;
                double cx = ox + 8.0;
                double cz = oz + 8.0;
                if (cx * cx + cz * cz > maxDistanceSq) continue;

                Vector3f offset = new Vector3f((float) ox, (float) oy, (float) oz);
                for (PlacementMesh.GpuLayer layer : section.layers()) {
                    if (layer.layer() == null || layer.vertexBuffer().isClosed()) continue;

                    final int slot = transforms.size();
                    transforms.add(new DynamicGpuData.Transform(modelView, colorModulator, offset, textureMatrix));
                    renderMap.get(layer.layer()).add(new RenderPass.Draw<>(
                            0, layer.vertexBuffer(), null, null, 0, layer.indexCount(), 0,
                            (slices, uploader) -> uploader.setUniform("DynamicTransforms", slices[slot])));

                    if (layer.indexCount() > maxIndexCount.get()) {
                        maxIndexCount.set(layer.indexCount());
                    }
                    added = true;
                }
            }
        }

        return added;
    }


    public static void drawOverlays(OverlayRenderType type, Camera camera) {
        if (MESHES.isEmpty()) return;

        boolean renderThrough = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue() ||
                                Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld();
        RenderPipeline pipeline = renderThrough ? type.renderThrough() : type.pipeline();
        Vec3 cameraPos = camera.position();
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = target.getColorTextureView();
        GpuTextureView depthTexture = target.getDepthTextureView();
        RenderSystem.AutoStorageIndexBuffer sequentialIndices = RenderSystem.getSequentialBuffer(type.topology());
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();

        int renderDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double maxDistanceSq = (double) renderDistance * renderDistance;

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            if (mesh.active.isEmpty() || !placement.isEnabled() || !placement.isRenderingEnabled()) continue;

            BlockPos delta = placement.getOrigin().subtract(mesh.activeBuildOrigin);
            Vec3 lag = MoveInterpolator.getLag(placement);

            for (PlacementMesh.GpuSection section : mesh.active.values()) {
                double ox = section.origin().getX() + delta.getX() + lag.x - cameraPos.x;
                double oy = section.origin().getY() + delta.getY() + lag.y - cameraPos.y;
                double oz = section.origin().getZ() + delta.getZ() + lag.z - cameraPos.z;
                double cx = ox + 8.0;
                double cz = oz + 8.0;
                if (cx * cx + cz * cz > maxDistanceSq) continue;

                for (PlacementMesh.GpuLayer layer : section.layers()) {
                    if (layer.overlay() != type || layer.indexCount() <= 0 || layer.vertexBuffer().isClosed()) continue;

                    modelViewStack.pushMatrix();
                    modelViewStack.translate((float) ox, (float) oy, (float) oz);
                    GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(
                            RenderSystem.getModelViewMatrixCopy(), new Vector4f(1f, 1f, 1f, 1f), new Vector3f(), new Matrix4f());
                    modelViewStack.popMatrix();

                    assert colorTexture != null;
                    try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                            () -> "litematicafastmove:placement_overlay", colorTexture, Optional.empty(), depthTexture, OptionalDouble.empty())) {
                        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transform);
                        pass.setVertexBuffer(0, layer.vertexBuffer().slice());
                        pass.setIndexBuffer(sequentialIndices.getBuffer(layer.indexCount()), sequentialIndices.type());
                        pass.drawIndexed(layer.indexCount(), 1, 0, 0, 0);
                    }
                }
            }
        }
    }
}
