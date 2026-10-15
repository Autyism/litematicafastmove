package ml.mypals.litematicafastmove.mesh;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkAccess;


public class ClientRegions {

    static boolean isEmpty(ClientLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
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

    static boolean isSectionEmpty(ClientLevel level, BlockPos min, BlockPos max, long key, BlockPos delta) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        int x0 = Math.max(sx << 4, min.getX()) - 1, x1 = Math.min((sx << 4) + 15, max.getX()) + 1;
        int y0 = Math.max(sy << 4, min.getY()) - 1, y1 = Math.min((sy << 4) + 15, max.getY()) + 1;
        int z0 = Math.max(sz << 4, min.getZ()) - 1, z1 = Math.min((sz << 4) + 15, max.getZ()) + 1;
        if (x0 > x1 || y0 > y1 || z0 > z1) return true;
        return isEmpty(level, x0 + delta.getX(), y0 + delta.getY(), z0 + delta.getZ(),
                x1 + delta.getX(), y1 + delta.getY(), z1 + delta.getZ());
    }
}
