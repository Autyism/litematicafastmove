package ml.mypals.litematicafastmove.verify;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class AsyncVerifyResult {
    public final ChunkPos chunkPos;
    public final int mismatchedCount;
    public final int missingCount;
    public final int extraCount;
    public final List<BlockPos> mismatchedPositions;

    public AsyncVerifyResult(ChunkPos chunkPos, int mismatchedCount, int missingCount, int extraCount, List<BlockPos> mismatchedPositions) {
        this.chunkPos = chunkPos;
        this.mismatchedCount = mismatchedCount;
        this.missingCount = missingCount;
        this.extraCount = extraCount;
        this.mismatchedPositions = mismatchedPositions;
    }

    private static final Map<ChunkPos, AsyncVerifyResult> RESULTS = new ConcurrentHashMap<>();

    public static void putResult(ChunkPos pos, AsyncVerifyResult result) {
        RESULTS.put(pos, result);
    }

    public static AsyncVerifyResult getResult(ChunkPos pos) {
        return RESULTS.get(pos);
    }

    public static void clear() {
        RESULTS.clear();
    }
}

