package ml.mypals.litematicafastmove.mesh;

import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;


public class PlacementBlockEntities {
    public static void forEachInChunk(int chunkX, int chunkZ, LongConsumer consumer) {
        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : PlacementMeshManager.meshes().entrySet()) {
            SchematicPlacement placement = entry.getKey();
            PlacementMesh mesh = entry.getValue();
            BlockPos buildOrigin = mesh.blockEntityBuildOrigin;
            if (buildOrigin == null || mesh.blockEntities.isEmpty() || !placement.isRenderingEnabled()) continue;

            BlockPos offset = origin(placement).subtract(buildOrigin);
            for (long[] positions : mesh.blockEntities.values()) {
                for (long packed : positions) {
                    int x = BlockPos.getX(packed) + offset.getX();
                    int z = BlockPos.getZ(packed) + offset.getZ();
                    if ((x >> 4) == chunkX && (z >> 4) == chunkZ) {
                        consumer.accept(BlockPos.asLong(x, BlockPos.getY(packed) + offset.getY(), z));
                    }
                }
            }
        }
    }

    static void replace(SchematicPlacement placement, PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer full, Set<Long> changedChunks) {
        BlockPos origin = origin(placement);
        BlockPos oldBuildOrigin = mesh.blockEntityBuildOrigin;
        if (oldBuildOrigin != null) {
            BlockPos oldOffset = origin.subtract(oldBuildOrigin);
            mesh.blockEntities.values().forEach(positions -> addChunksOf(positions, oldOffset, changedChunks));
        }

        mesh.blockEntities.clear();
        mesh.blockEntityBuildOrigin = full.buildOrigin;
        BlockPos offset = origin.subtract(full.buildOrigin);
        for (PlacementMesh.GpuSection section : full.uploaded) {
            if (section.blockEntities().length > 0) {
                mesh.blockEntities.put(section.key(), section.blockEntities());
                addChunksOf(section.blockEntities(), offset, changedChunks);
            }
        }
    }

    static void update(SchematicPlacement placement, PlacementMesh mesh, PlacementMesh.BuiltPlacementBuffer partial, Set<Long> changedChunks) {
        BlockPos offset = origin(placement).subtract(partial.buildOrigin);
        Map<Long, long[]> updated = new HashMap<>();
        for (PlacementMesh.GpuSection section : partial.uploaded) {
            updated.put(section.key(), section.blockEntities());
        }

        for (long key : partial.keys) {
            long[] before = mesh.blockEntities.get(key);
            long[] after = updated.get(key);
            if (after != null && after.length == 0) after = null;
            if (Arrays.equals(before, after)) continue;

            addChunksOf(before, offset, changedChunks);
            addChunksOf(after, offset, changedChunks);
            if (after != null) {
                mesh.blockEntities.put(key, after);
            } else {
                mesh.blockEntities.remove(key);
            }
        }
    }

    static void scheduleLitematicaChunks(Set<Long> chunks) {
        if (chunks.isEmpty()) return;

        IWorldSchematicRenderer renderer = LitematicaRenderer.getInstance().getWorldRenderer();
        for (long packed : chunks) {
            renderer.scheduleChunkRenders(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
        }
    }

    private static BlockPos origin(SchematicPlacement placement) {
        PendingMoveTracker.MoveState pending = PendingMoveTracker.get(placement);
        return pending != null ? pending.baseOrigin : placement.getOrigin();
    }

    private static void addChunksOf(long[] positions, BlockPos offset, Set<Long> out) {
        if (positions == null) return;
        for (long packed : positions) {
            out.add(ChunkPos.asLong((BlockPos.getX(packed) + offset.getX()) >> 4, (BlockPos.getZ(packed) + offset.getZ()) >> 4));
        }
    }
}
