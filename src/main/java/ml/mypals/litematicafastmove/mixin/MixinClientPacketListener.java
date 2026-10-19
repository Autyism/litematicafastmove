package ml.mypals.litematicafastmove.mixin;

import ml.mypals.litematicafastmove.mesh.PlacementMeshManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        PlacementMeshManager.markChunkDirty(packet.getX(), packet.getZ());
    }
}
