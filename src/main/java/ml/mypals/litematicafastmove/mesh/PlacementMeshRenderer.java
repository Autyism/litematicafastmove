package ml.mypals.litematicafastmove.mesh;

import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import ml.mypals.litematicafastmove.access.PlacementBlockAccessor;
import ml.mypals.litematicafastmove.sync.MoveInterpolator;
import ml.mypals.litematicafastmove.sync.PendingMoveTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

public final class PlacementMeshRenderer {
    private interface SectionVisitor {
        void visit(PlacementMesh.GpuSection section, double ox, double oy, double oz);
    }

    private record TranslucentDraw(double distanceSq, PlacementMesh.GpuLayer layer, Vector3f offset) {}

    private static volatile Frustum frustum;

    private PlacementMeshRenderer() {}

    public static void captureFrustum(Frustum value) {
        frustum = value;
    }

    public static boolean appendDraws(Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                      List<DynamicUniforms.Transform> transforms,
                                      Matrix4fc modelView, Vector4fc colorModulator, Matrix4fc textureMatrix,
                                      double cameraX, double cameraY, double cameraZ,
                                      LocalIntRef maxIndexCount) {
        MeshUploader.processQueues();
        if (PlacementMeshManager.meshes().isEmpty()) return false;
        MoveInterpolator.update();

        boolean[] added = {false};
        TranslucentSorter sorter = TranslucentSorter.forFrame();
        List<TranslucentDraw> translucentDraws = new ArrayList<>();

        forEachVisibleSection(cameraX, cameraY, cameraZ, true, (section, ox, oy, oz) -> {
            Vector3f offset = new Vector3f((float) ox, (float) oy, (float) oz);

            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.layer() == null || layer.vertexBuffer().isClosed()) continue;

                if (layer.sort() != null) {
                    sorter.maybeResort(layer, -ox, -oy, -oz);
                    translucentDraws.add(new TranslucentDraw((ox + 8) * (ox + 8) + (oy + 8) * (oy + 8) + (oz + 8) * (oz + 8), layer, offset));
                    continue;
                }

                addDraw(renderMap, transforms, modelView, colorModulator, textureMatrix, maxIndexCount, layer, offset);
                added[0] = true;
            }
        });

        translucentDraws.sort(Comparator.comparingDouble(TranslucentDraw::distanceSq));//Sort transparent...
        for (TranslucentDraw draw : translucentDraws) {
            addDraw(renderMap, transforms, modelView, colorModulator, textureMatrix, maxIndexCount, draw.layer(), draw.offset());
            added[0] = true;
        }

        return added[0];
    }

    private static void addDraw(Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> renderMap,
                                List<DynamicUniforms.Transform> transforms,
                                Matrix4fc modelView, Vector4fc colorModulator, Matrix4fc textureMatrix,
                                LocalIntRef maxIndexCount, PlacementMesh.GpuLayer layer, Vector3f offset) {
        final int slot = transforms.size();
        transforms.add(new DynamicUniforms.Transform(modelView, colorModulator, offset, textureMatrix));
        renderMap.get(layer.layer()).add(new RenderPass.Draw<>(0, layer.vertexBuffer(), layer.indexBuffer(),
                layer.indexType(), 0, layer.indexCount(), (slices, uploader) -> uploader.upload("DynamicTransforms", slices[slot])));

        if (layer.indexBuffer() == null && layer.indexCount() > maxIndexCount.get()) {
            maxIndexCount.set(layer.indexCount());
        }
    }

    public static void drawOverlays(OverlayRenderType type, Camera camera) {
        if (PlacementMeshManager.meshes().isEmpty()) return;

        Vec3 cameraPos = camera.position();
        Matrix4f viewMatrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        Vector4f colorModulator = new Vector4f(1f, 1f, 1f, 1f);
        Matrix4f textureMatrix = new Matrix4f();
        List<RenderPass.Draw<GpuBufferSlice[]>> draws = new ArrayList<>();
        List<DynamicUniforms.Transform> transforms = new ArrayList<>();
        int[] maxIndexCount = {0};

        forEachVisibleSection(cameraPos.x, cameraPos.y, cameraPos.z, false, (section, ox, oy, oz) -> {
            for (PlacementMesh.GpuLayer layer : section.layers()) {
                if (layer.overlay() != type || layer.indexCount() <= 0 || layer.vertexBuffer().isClosed()) continue;

                final int slot = transforms.size();
                Matrix4f modelView = new Matrix4f(viewMatrix).translate((float) ox, (float) oy, (float) oz);
                transforms.add(new DynamicUniforms.Transform(modelView, colorModulator, new Vector3f(), textureMatrix));
                draws.add(new RenderPass.Draw<>(0, layer.vertexBuffer(), null, null, 0, layer.indexCount(),
                        (slices, uploader) -> uploader.upload("DynamicTransforms", slices[slot])));
                maxIndexCount[0] = Math.max(maxIndexCount[0], layer.indexCount());
            }
        });

        if (draws.isEmpty()) return;

        boolean renderThrough = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue() ||
                                Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld();
        RenderPipeline pipeline = renderThrough ? type.getRenderThrough() : type.getPipeline();
        // 和 Litematica 0.26 画覆盖层用同一个目标：装了 LitematicaPass 时这里拿到的是它自己的目标，
        // 在 EXIT 的 portal 里渲染通道会被挪到 EXIT 的目标上
        RenderTarget target = fi.dy.masa.malilib.render.RenderUtils.fb();
        GpuTextureView colorTexture = target.getColorTextureView();
        GpuTextureView depthTexture = target.useDepth ? target.getDepthTextureView() : null;
        RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(type.getDrawMode());
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms.toArray(new DynamicUniforms.Transform[0]));

        assert colorTexture != null;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "litematicafastmove:placement_overlay", colorTexture, OptionalInt.empty(), depthTexture, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.drawMultipleIndexed(draws, autoStorageIndexBuffer.getBuffer(maxIndexCount[0]), autoStorageIndexBuffer.type(),
                    List.of("DynamicTransforms"), slices);
        }
    }

    private static void forEachVisibleSection(double cameraX, double cameraY, double cameraZ, boolean requestClipRebuilds, SectionVisitor visitor) {
        LayerRangeClip.Range range = LayerRangeClip.snapshot();
        Frustum fr = frustum;
        int renderDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 2) * 16;
        double maxDistanceSq = (double) renderDistance * renderDistance;

        for (Map.Entry<SchematicPlacement, PlacementMesh> entry : PlacementMeshManager.meshes().entrySet()) {
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
                    PlacementMesh.Clip required = LayerRangeClip.compute(range, accessor.minPos, accessor.maxPos, section.origin(), delta);
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
                PlacementMeshManager.requestPartialBuild(placement, mesh, clipKeys);
            }
        }
    }
}
