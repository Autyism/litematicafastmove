package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingMoveTracker {
    public static final class MoveState {
        public final BlockPos baseOrigin;
        public final Set<ChunkPos> baseChunks;
        public volatile long lastMoveMs;

        MoveState(BlockPos baseOrigin, Set<ChunkPos> baseChunks) {
            this.baseOrigin = baseOrigin;
            this.baseChunks = baseChunks;
        }
    }

    private static final Map<SchematicPlacement, MoveState> STATES = new ConcurrentHashMap<>();
    private static final Set<Long> SKIPPED_REBUILDS = ConcurrentHashMap.newKeySet();
    private static volatile Set<Long> frozenChunks = Set.of();
    private static volatile List<ShiftedBox> shiftedBoxes = List.of();

    private record ShiftedBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                              SchematicPlacement placement, BlockPos baseOrigin) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public static boolean hasAnyPending() {
        return !STATES.isEmpty();
    }

    public static MoveState get(SchematicPlacement placement) {
        return STATES.get(placement);
    }

    public static void onMoved(SchematicPlacement placement, BlockPos baseOrigin, Set<ChunkPos> baseChunks) {
        MoveState state = STATES.computeIfAbsent(placement, p -> new MoveState(baseOrigin, baseChunks));
        state.lastMoveMs = System.currentTimeMillis();
        rebuildFrozen();
    }

    public static MoveState remove(SchematicPlacement placement) {
        MoveState state = STATES.remove(placement);
        if (state != null) {
            rebuildFrozen();
        }
        return state;
    }

    public static List<SchematicPlacement> getPending() {
        return new ArrayList<>(STATES.keySet());
    }

    public static List<SchematicPlacement> getDueForCommit(long now, long settleMs) {
        List<SchematicPlacement> due = new ArrayList<>();
        STATES.forEach((placement, state) -> {
            if (now - state.lastMoveMs >= settleMs) due.add(placement);
        });
        return due;
    }

    public static void clear() {
        STATES.clear();
        SKIPPED_REBUILDS.clear();
        frozenChunks = Set.of();
        shiftedBoxes = List.of();
    }

    public static boolean hasAnyObjectShift() {
        return !shiftedBoxes.isEmpty() || MoveInterpolator.hasLagging();
    }

    public static Vec3 getObjectShift(double x, double y, double z) {
        List<ShiftedBox> boxes = shiftedBoxes;
        if (boxes.isEmpty()) return MoveInterpolator.getLagShiftAt(x, y, z);

        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (ShiftedBox box : boxes) {
            if (box.contains(bx, by, bz)) {
                BlockPos delta = box.placement().getOrigin().subtract(box.baseOrigin());
                return MoveInterpolator.getLag(box.placement()).add(delta.getX(), delta.getY(), delta.getZ());
            }
        }
        return MoveInterpolator.getLagShiftAt(x, y, z);
    }

    public static boolean isFrozenChunk(int chunkX, int chunkZ) {
        Set<Long> frozen = frozenChunks;
        return !frozen.isEmpty() && frozen.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void markSkippedRebuild(int chunkX, int chunkZ) {
        SKIPPED_REBUILDS.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static List<ChunkPos> drainUnfrozenSkipped() {
        List<ChunkPos> result = new ArrayList<>();
        for (Long key : SKIPPED_REBUILDS) {
            ChunkPos pos = new ChunkPos(key);
            if (!isFrozenChunk(pos.x, pos.z) && SKIPPED_REBUILDS.remove(key)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static void rebuildFrozen() {
        Set<Long> frozen = new HashSet<>();
        List<ShiftedBox> boxes = new ArrayList<>();

        STATES.forEach((placement, state) -> {
            BlockPos delta = placement.getOrigin().subtract(state.baseOrigin);

            for (var box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                assert box.getPos1() != null;
                BlockPos min = box.getPos1().subtract(delta);
                assert box.getPos2() != null;
                BlockPos max = box.getPos2().subtract(delta);
                boxes.add(new ShiftedBox(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()),
                        Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()),
                        placement, state.baseOrigin));
            }

            for (ChunkPos pos : state.baseChunks) {
                frozen.add(pos.toLong());
            }
        });

        frozenChunks = Set.copyOf(frozen);
        shiftedBoxes = List.copyOf(boxes);
    }
}
