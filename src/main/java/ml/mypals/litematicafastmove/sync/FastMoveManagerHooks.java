package ml.mypals.litematicafastmove.sync;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;


public interface FastMoveManagerHooks {
    void litematicafastmove$commit(SchematicPlacement placement);
    void litematicafastmove$commitAll();
    void litematicafastmove$commitSettled(long nowMs);
}
