package ml.mypals.litematicafastmove.access;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;


public class RegionTransform {
    public final String regionName;
    public final BlockPos regionMinWorld;
    public final BlockPos regionMaxWorld;
    public final Vec3i size;
    public final Rotation combinedRotation;
    public final Mirror mainMirror;
    public final Mirror subMirror;
    public final Mirror schematicMirror;
    public final Rotation schematicRotation;
    public final Mirror subRegionMirror;
    public final Rotation subRegionRotation;
    public final BlockPos totalRegionPosTransformed;
    public final BlockPos posMinRel;
    public final BlockPos regionPos;

    public RegionTransform(SchematicPlacement schematicPlacement, SubRegionPlacement placement, String regionName, Vec3i size) {
        this.regionName = regionName;
        this.size = size;

        BlockPos origin = schematicPlacement.getOrigin();
        this.regionPos = placement.getPos();

        BlockPos relEnd = PositionUtils.getRelativeEndPositionFromAreaSize(size);
        BlockPos posEndRel = (new BlockPos(relEnd.getX(), relEnd.getY(), relEnd.getZ())).offset(this.regionPos);
        this.posMinRel = PositionUtils.getMinCorner(this.regionPos, posEndRel);

        BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(this.regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
        this.totalRegionPosTransformed = regionPosTransformed.offset(origin);

        this.combinedRotation = schematicPlacement.getRotation().getRotated(placement.getRotation());
        this.mainMirror = schematicPlacement.getMirror();
        Mirror sMirror = placement.getMirror();
        if (sMirror != Mirror.NONE &&
                (schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
                        schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90)) {
            sMirror = (sMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK);
        }
        this.subMirror = sMirror;
        this.schematicMirror = schematicPlacement.getMirror();
        this.schematicRotation = schematicPlacement.getRotation();
        this.subRegionMirror = placement.getMirror();
        this.subRegionRotation = placement.getRotation();

        BlockPos boxOriginAbsolute = regionPosTransformed.offset(origin);
        BlockPos pos2 = PositionUtils.getRelativeEndPositionFromAreaSize(size);
        pos2 = PositionUtils.getTransformedBlockPos(pos2, schematicPlacement.getMirror(), schematicPlacement.getRotation());
        pos2 = PositionUtils.getTransformedBlockPos(pos2, placement.getMirror(), placement.getRotation()).offset(boxOriginAbsolute);

        this.regionMinWorld = PositionUtils.getMinCorner(boxOriginAbsolute, pos2);
        this.regionMaxWorld = PositionUtils.getMaxCorner(boxOriginAbsolute, pos2);
    }

    public boolean containsWorld(int x, int y, int z) {
        return x >= regionMinWorld.getX() && x <= regionMaxWorld.getX() &&
               y >= regionMinWorld.getY() && y <= regionMaxWorld.getY() &&
               z >= regionMinWorld.getZ() && z <= regionMaxWorld.getZ();
    }

    public boolean worldToLocal(int wx, int wy, int wz, BlockPos.MutableBlockPos outPos) {
        if (!containsWorld(wx, wy, wz)) {
            return false;
        }

        int relX = wx - totalRegionPosTransformed.getX();
        int relY = wy - totalRegionPosTransformed.getY();
        int relZ = wz - totalRegionPosTransformed.getZ();

        BlockPos p = new BlockPos(relX, relY, relZ);
        p = PositionUtils.getReverseTransformedBlockPos(p, subRegionMirror, subRegionRotation);
        p = PositionUtils.getReverseTransformedBlockPos(p, schematicMirror, schematicRotation);

        int lx = p.getX() - (posMinRel.getX() - regionPos.getX());
        int ly = p.getY() - (posMinRel.getY() - regionPos.getY());
        int lz = p.getZ() - (posMinRel.getZ() - regionPos.getZ());

        if (lx < 0 || ly < 0 || lz < 0 || lx >= Math.abs(size.getX()) || ly >= Math.abs(size.getY()) || lz >= Math.abs(size.getZ())) {
            return false;
        }

        outPos.set(lx, ly, lz);
        return true;
    }
}

