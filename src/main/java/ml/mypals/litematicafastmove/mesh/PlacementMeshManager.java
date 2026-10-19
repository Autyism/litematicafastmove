package ml.mypals.litematicafastmove.mesh;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public final class PlacementMeshManager {
    private static final Map<SchematicPlacement, PlacementMesh> MESHES = new ConcurrentHashMap<>();
    private static final Set<Long> DIRTY_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> DIRTY_BLOCKS = ConcurrentHashMap.newKeySet();
    private static final Set<SchematicPlacement> DIRTY_PLACEMENTS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();


    private PlacementMeshManager() {}

    static Map<SchematicPlacement, PlacementMesh> meshes() {
        return MESHES;
    }

    public static boolean isDetached(SchematicPlacement placement) {
        return FastMoveConfig.isRenderTakenOver(placement);
    }

    public static void requestBuild(SchematicPlacement placement) {
        if (placement == null) return;
        if (!FastMoveConfig.isFastMoveEnabled() || !isDetached(placement)) {
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

        MeshSectionBuilder.submit(placement, mesh, generation, accessor, buildOrigin, null, BlockPos.ZERO, world, clientLevel,
                built -> MeshUploader.enqueueBuilt(mesh, built));
        MeshDebugGizmos.fullBuild(accessor, BlockPos.ZERO, false);
    }

    static void requestPartialBuild(SchematicPlacement placement, PlacementMesh mesh, Set<Long> keys) {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        ClientLevel clientLevel = Minecraft.getInstance().level;
        PlacementBlockAccessor accessor = mesh.targetAccessor;
        BlockPos buildOrigin = mesh.targetBuildOrigin;
        if (world == null || clientLevel == null || accessor == null || buildOrigin == null) return;

        BlockPos clientDelta = placement.getOrigin().subtract(buildOrigin);
        MeshSectionBuilder.submit(placement, mesh, mesh.requestedGeneration, accessor, buildOrigin, keys, clientDelta, world, clientLevel,
                built -> MeshUploader.enqueueBuilt(mesh, built));
        MeshDebugGizmos.sectionsQueued(accessor, keys, clientDelta);
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
            MeshUploader.enqueueClose(mesh);
        }
    }

    public static void disposeAll() {
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            dispose(placement);
        }
    }

    /**
     * 周期性同步，补建缺失的网格 (例如进入世界时WorldSchematic尚未创建)
     * 释放已移除或者不再被接管的投影的网格
     */
    public static void tick() {
        var manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return;

        Collection<SchematicPlacement> all = manager.getAllSchematicsPlacements();
        for (SchematicPlacement placement : new ArrayList<>(MESHES.keySet())) {
            if (!all.contains(placement)) {
                dispose(placement);
            }
        }

        if (!FastMoveConfig.isFastMoveEnabled()) {
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

    /**
     * 真实世界单个方块变化时只重建包含它及相邻一格的区段
     */
    public static void markBlockDirty(BlockPos pos) {
        if (!MESHES.isEmpty()) {
            DIRTY_BLOCKS.add(pos.asLong());
        }
    }

    /**
     * 真实世界区块加载，整列都变化
     */
    public static void markChunkDirty(int chunkX, int chunkZ) {
        if (!MESHES.isEmpty()) {
            DIRTY_CHUNKS.add(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    /**
     * 投影移到新位置，全部区段按新位置重新对照，前后都是空气的区段会被跳过
     */
    public static void markPlacementDirty(SchematicPlacement placement) {
        if (placement != null && MESHES.containsKey(placement)) {
            DIRTY_PLACEMENTS.add(placement);
        }
    }

    public static void processDirty() {
        if (DIRTY_CHUNKS.isEmpty() && DIRTY_BLOCKS.isEmpty() && DIRTY_PLACEMENTS.isEmpty()) return;

        List<Long> chunks = drain(DIRTY_CHUNKS);
        List<Long> blocks = drain(DIRTY_BLOCKS);
        List<SchematicPlacement> placements = drain(DIRTY_PLACEMENTS);

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : MESHES.entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            PlacementBlockAccessor accessor = mesh.targetAccessor;
            BlockPos buildOrigin = mesh.targetBuildOrigin;
            if (accessor == null || buildOrigin == null) continue;

            BlockPos delta = placement.getOrigin().subtract(buildOrigin);
            Set<Long> keys = placements.contains(placement) ? allSections(accessor) : new HashSet<>();
            addSectionsInChunks(accessor, chunks, delta, keys);
            addSectionsAroundBlocks(accessor, blocks, delta, keys);
            skipStillEmptySections(mesh, accessor, keys, delta);

            if (!keys.isEmpty()) {
                requestPartialBuild(placement, mesh, keys);
            }
        }
    }

    private static <T> List<T> drain(Set<T> set) {
        List<T> drained = new ArrayList<>(set);
        drained.forEach(set::remove);
        return drained;
    }

    private static Set<Long> allSections(PlacementBlockAccessor accessor) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;
        Set<Long> keys = new HashSet<>();
        for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
            for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
                for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                    keys.add(SectionPos.asLong(sx, sy, sz));
                }
            }
        }
        return keys;
    }

    private static void addSectionsInChunks(PlacementBlockAccessor accessor, List<Long> chunks, BlockPos delta, Set<Long> keys) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

        for (long packed : chunks) {
            ChunkPos chunk = new ChunkPos(packed);
            int bx0 = Math.max((chunk.x << 4) - delta.getX(), min.getX());
            int bx1 = Math.min((chunk.x << 4) + 15 - delta.getX(), max.getX());
            int bz0 = Math.max((chunk.z << 4) - delta.getZ(), min.getZ());
            int bz1 = Math.min((chunk.z << 4) + 15 - delta.getZ(), max.getZ());
            if (bx0 > bx1 || bz0 > bz1) continue;

            for (int sx = bx0 >> 4; sx <= bx1 >> 4; sx++) {
                for (int sz = bz0 >> 4; sz <= bz1 >> 4; sz++) {
                    for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
                        keys.add(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
    }

    // overlay 判断边线与面时会看相邻一格的真实方块，所以变化位置周围一格所在的区段都要重建
    private static void addSectionsAroundBlocks(PlacementBlockAccessor accessor, List<Long> blocks, BlockPos delta, Set<Long> keys) {
        BlockPos min = accessor.minPos;
        BlockPos max = accessor.maxPos;

        for (long packed : blocks) {
            int bx = BlockPos.getX(packed) - delta.getX();
            int by = BlockPos.getY(packed) - delta.getY();
            int bz = BlockPos.getZ(packed) - delta.getZ();
            int x0 = Math.max(bx - 1, min.getX()), x1 = Math.min(bx + 1, max.getX());
            int y0 = Math.max(by - 1, min.getY()), y1 = Math.min(by + 1, max.getY());
            int z0 = Math.max(bz - 1, min.getZ()), z1 = Math.min(bz + 1, max.getZ());
            if (x0 > x1 || y0 > y1 || z0 > z1) continue;

            for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                        keys.add(SectionPos.asLong(sx, sy, sz));
                    }
                }
            }
        }
    }

    /**
     * 区段的网格只取决于投影方块和对应的真实方块；构建时与现在对应的真实区域都是纯空气时，
     * 重建结果完全相同 (例如在空中挪动投影选位置)，不必重建
     */
    private static void skipStillEmptySections(PlacementMesh mesh, PlacementBlockAccessor accessor, Set<Long> keys, BlockPos delta) {
        if (keys.isEmpty() || mesh.uploadingFull != null || !mesh.deferredPartials.isEmpty() || !mesh.uploadingPartials.isEmpty() ||
            mesh.activeBuildOrigin == null || !mesh.activeBuildOrigin.equals(mesh.targetBuildOrigin)) return;

        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) return;

        List<Long> skipped = new ArrayList<>();
        keys.removeIf(key -> {
            PlacementMesh.GpuSection section = mesh.active.get(key);
            boolean wasEmpty = section == null || section.clientEmpty();
            boolean skip = wasEmpty && ClientRegions.isSectionEmpty(clientLevel, accessor.minPos, accessor.maxPos, key, delta);
            if (skip) skipped.add(key);
            return skip;
        });
        MeshDebugGizmos.sectionsSkipped(accessor, skipped, delta);
    }
}
