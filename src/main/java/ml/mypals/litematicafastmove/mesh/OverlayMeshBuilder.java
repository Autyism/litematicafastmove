package ml.mypals.litematicafastmove.mesh;

import com.mojang.blaze3d.vertex.BufferBuilder;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.render.RenderUtils;
import fi.dy.masa.litematica.render.schematic.OverlayRenderType;
import fi.dy.masa.litematica.util.IgnoreBlockRegistry;
import fi.dy.masa.litematica.util.OverlayType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.malilib.util.game.BlockUtils;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.Function;

/**
 * 在独立网格中生成 overlay（错误 / 缺失 / 多余方块提示），逻辑移植自 Litematica 的
 * ChunkRendererSchematicVbo#renderOverlay，使大型投影的 overlay 与方块一样属于自己的 Buffer，
 * 始终随投影一起移动。
 *
 * 坐标约定：pos 为构建坐标；客户端世界中的对应位置 = pos + clientDelta；
 * 顶点写入相对区段原点的位置 (x & 15, y & 15, z & 15)。
 */
final class OverlayMeshBuilder {
    interface ClientView {
        BlockState get(BlockPos buildPos);
    }

    private final DetachedBlockView schematic;
    private final ClientView client;
    private final Function<OverlayRenderType, BufferBuilder> buffers;

    private final boolean enableSides = Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_SIDES.getBooleanValue();
    private final boolean enableOutlines = Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_OUTLINES.getBooleanValue();
    private final boolean reducedInnerSides = Configs.Visuals.OVERLAY_REDUCED_INNER_SIDES.getBooleanValue();
    private final boolean modelSides = Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_SIDES.getBooleanValue();
    private final boolean modelOutline = Configs.Visuals.SCHEMATIC_OVERLAY_MODEL_OUTLINE.getBooleanValue();
    private final boolean renderFluids = Configs.Visuals.ENABLE_SCHEMATIC_FLUIDS.getBooleanValue();
    private final boolean translucentInnerSides = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue() &&
                                                  Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue();
    private final boolean overlayCulling = Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY_CULLING.getBooleanValue();
    private final boolean ignoreClientFluids = Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue();
    private final boolean ignoreCropAge = Configs.Visuals.IGNORE_CROP_AGE.getBooleanValue();
    private final boolean differentBlocks = Configs.Generic.ENABLE_DIFFERENT_BLOCKS.getBooleanValue();
    private final IgnoreBlockRegistry ignoreBlockRegistry = new IgnoreBlockRegistry();

    private final BlockPos.MutableBlockPos relPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();
    private final RandomSource rand = RandomSource.create();

    OverlayMeshBuilder(DetachedBlockView schematic, ClientView client, Function<OverlayRenderType, BufferBuilder> buffers) {
        this.schematic = schematic;
        this.client = client;
        this.buffers = buffers;
    }

    static boolean isEnabled() {
        return Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY.getBooleanValue();
    }

    void addOverlay(BlockPos pos, BlockState stateSchematic, BlockState stateClient, boolean missing) {
        OverlayType type = this.getOverlayType(stateSchematic, stateClient);
        Color4f color = getOverlayColor(type);
        if (color == null || this.shouldCullOverlayPos(pos, stateSchematic, stateClient)) return;

        this.relPos.set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);

        if (this.enableSides) {
            this.renderSides(type, color, pos, stateSchematic, missing);
        }
        if (this.enableOutlines) {
            this.renderOutlines(type, color, pos, stateSchematic, missing);
        }
    }

    private void renderSides(OverlayType type, Color4f color, BlockPos pos, BlockState stateSchematic, boolean missing) {
        BufferBuilder quads = this.buffers.apply(OverlayRenderType.QUAD);
        boolean useDefault = false;

        if (this.reducedInnerSides) {
            List<BlockModelPart> modelParts = this.getModelParts(pos, stateSchematic);

            if (RenderUtils.hasQuads(modelParts)) {
                VoxelShape shape = stateSchematic.getCollisionShape(this.schematic, pos);

                for (Direction side : fi.dy.masa.malilib.util.position.PositionUtils.ALL_DIRECTIONS) {
                    this.posMutable.set(pos.getX() + side.getStepX(), pos.getY() + side.getStepY(), pos.getZ() + side.getStepZ());
                    OverlayType typeAdj = this.getOverlayType(this.schematic.getBlockState(this.posMutable), this.client.get(this.posMutable));
                    boolean fullSquareSide = Block.isFaceFull(shape, side);

                    if (missing && this.modelSides) {
                        if (type.getRenderPriority() > typeAdj.getRenderPriority() || !fullSquareSide) {
                            for (BlockModelPart part : modelParts) {
                                RenderUtils.drawBlockModelQuadOverlayBatched(part, stateSchematic, this.relPos, side, color, 0, quads);
                            }
                        } else {
                            useDefault = true;
                        }
                    } else if (type.getRenderPriority() > typeAdj.getRenderPriority()) {
                        RenderUtils.drawBlockBoxSideBatchedQuads(this.relPos, side, color, 0, quads);
                    } else {
                        useDefault = true;
                    }
                }
            } else {
                useDefault = true;
            }
        } else if (missing && this.modelSides) {
            List<BlockModelPart> modelParts = this.getModelParts(pos, stateSchematic);
            if (RenderUtils.hasQuads(modelParts)) {
                RenderUtils.drawBlockModelQuadOverlayBatched(modelParts, stateSchematic, this.relPos, color, 0, quads);
            } else {
                useDefault = true;
            }
        } else {
            RenderUtils.drawBlockBoxBatchedQuads(this.relPos, color, 0, quads);
        }

        if (useDefault) {
            try {
                RenderUtils.drawBlockBoxBatchedQuads(this.relPos, color, 0, quads);
            } catch (Exception ignored) {
            }
        }
    }

    private void renderOutlines(OverlayType type, Color4f baseColor, BlockPos pos, BlockState stateSchematic, boolean missing) {
        BufferBuilder lines = this.buffers.apply(OverlayRenderType.OUTLINE);
        Color4f color = new Color4f(baseColor.r, baseColor.g, baseColor.b, 1f);
        float lineWidth = 1.0f;
        boolean useDefault = false;

        if (this.reducedInnerSides) {
            OverlayType[][][] adjTypes = new OverlayType[3][3][3];

            for (int y = 0; y <= 2; ++y) {
                for (int z = 0; z <= 2; ++z) {
                    for (int x = 0; x <= 2; ++x) {
                        if (x != 1 || y != 1 || z != 1) {
                            this.posMutable.set(pos.getX() + x - 1, pos.getY() + y - 1, pos.getZ() + z - 1);
                            adjTypes[x][y][z] = this.getOverlayType(this.schematic.getBlockState(this.posMutable), this.client.get(this.posMutable));
                        } else {
                            adjTypes[x][y][z] = type;
                        }
                    }
                }
            }

            if (missing && this.modelOutline) {
                if (stateSchematic.canOcclude()) {
                    useDefault = true;
                } else {
                    List<BlockModelPart> modelParts = this.getModelParts(pos, stateSchematic);
                    if (RenderUtils.hasQuads(modelParts)) {
                        RenderUtils.drawDebugBlockModelOutlinesBatched(modelParts, stateSchematic, this.relPos, color, 0, lineWidth, lines);
                    } else {
                        useDefault = true;
                    }
                }
            } else {
                this.renderReducedEdges(pos, adjTypes, baseColor, lineWidth, lines);
            }
        } else if (missing && this.modelOutline) {
            List<BlockModelPart> modelParts = this.getModelParts(pos, stateSchematic);
            if (RenderUtils.hasQuads(modelParts)) {
                RenderUtils.drawDebugBlockModelOutlinesBatched(modelParts, stateSchematic, this.relPos, color, 0, lineWidth, lines);
            } else {
                useDefault = true;
            }
        } else {
            useDefault = true;
        }

        if (useDefault) {
            try {
                RenderUtils.drawBlockBoundingBoxOutlinesBatchedDebugLines(this.relPos, color, 0, lineWidth, lines);
            } catch (Exception ignored) {
            }
        }
    }

    private void renderReducedEdges(BlockPos pos, OverlayType[][][] adjTypes, Color4f color, float lineWidth, BufferBuilder lines) {
        OverlayType[] neighborTypes = new OverlayType[4];
        Vec3i[] neighborPositions = new Vec3i[4];

        for (Direction.Axis axis : PositionUtils.AXES_ALL) {
            for (int corner = 0; corner < 4; ++corner) {
                Vec3i[] offsets = PositionUtils.getEdgeNeighborOffsets(axis, corner);
                if (offsets == null) continue;

                int index = -1;
                boolean hasCurrent = false;

                for (int i = 0; i < 4; ++i) {
                    Vec3i offset = offsets[i];
                    OverlayType type = adjTypes[offset.getX() + 1][offset.getY() + 1][offset.getZ() + 1];
                    if (type == OverlayType.NONE) continue;

                    if (index == -1 || type.getRenderPriority() >= neighborTypes[index - 1].getRenderPriority()) {
                        if (index < 0 || type.getRenderPriority() > neighborTypes[index - 1].getRenderPriority()) {
                            index = 0;
                        }
                        neighborPositions[index] = new Vec3i(pos.getX() + offset.getX(), pos.getY() + offset.getY(), pos.getZ() + offset.getZ());
                        neighborTypes[index] = type;
                        hasCurrent |= (i == 0);
                        ++index;
                    }
                }

                if (index > 0 && hasCurrent) {
                    Vec3i posTmp = new Vec3i(pos.getX(), pos.getY(), pos.getZ());
                    for (int i = 0; i < index; ++i) {
                        Vec3i tmp = neighborPositions[i];
                        if (tmp.getX() <= posTmp.getX() && tmp.getY() <= posTmp.getY() && tmp.getZ() <= posTmp.getZ()) {
                            posTmp = tmp;
                        }
                    }

                    if (posTmp.getX() == pos.getX() && posTmp.getY() == pos.getY() && posTmp.getZ() == pos.getZ()) {
                        try {
                            RenderUtils.drawBlockBoxEdgeBatchedDebugLines(this.relPos, axis, corner, color, lineWidth, lines);
                        } catch (IllegalStateException ignored) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private boolean shouldCullOverlayPos(BlockPos pos, BlockState stateSchematic, BlockState stateClient) {
        if (!stateSchematic.getFluidState().isEmpty() && !this.renderFluids) {
            return true;
        }
        if (this.translucentInnerSides) {
            return false;
        }
        if (this.overlayCulling && stateClient.is(BlockTags.AIR)) {
            for (Direction side : fi.dy.masa.malilib.util.position.PositionUtils.ALL_DIRECTIONS) {
                // 层范围外在裁剪视图中是空气，裁切面会自然判定为可见
                if (Block.shouldRenderFace(stateSchematic, this.schematic.getBlockState(pos.relative(side)), side)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    OverlayType getOverlayType(BlockState stateSchematic, BlockState stateClient) {
        if (stateSchematic == stateClient) {
            return OverlayType.NONE;
        }

        boolean clientHasAir = stateClient.isAir();

        if (stateSchematic.isAir()) {
            if (clientHasAir ||
                (this.ignoreClientFluids && stateClient.liquid()) ||
                this.ignoreBlockRegistry.hasBlock(stateClient.getBlock())) {
                return OverlayType.NONE;
            }
            return OverlayType.EXTRA;
        }

        if (clientHasAir || (this.ignoreClientFluids && stateClient.liquid())) {
            return OverlayType.MISSING;
        }
        if (stateSchematic.getBlock() != stateClient.getBlock()) {
            if (this.differentBlocks && BlockUtils.isInSameGroup(stateSchematic, stateClient)) {
                return BlockUtils.matchPropertiesOnly(stateSchematic, stateClient) ? OverlayType.DIFF_BLOCK : OverlayType.WRONG_STATE;
            }
            return OverlayType.WRONG_BLOCK;
        }
        if (this.ignoreCropAge && fi.dy.masa.litematica.util.BlockUtils.areStatesEqualIgnoringAge(stateSchematic, stateClient)) {
            return OverlayType.NONE;
        }
        return OverlayType.WRONG_STATE;
    }

    private static Color4f getOverlayColor(OverlayType type) {
        return switch (type) {
            case MISSING -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getColor() : null;
            case EXTRA -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_EXTRA.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_EXTRA.getColor() : null;
            case WRONG_BLOCK -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_WRONG_BLOCK.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_BLOCK.getColor() : null;
            case WRONG_STATE -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_WRONG_STATE.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_STATE.getColor() : null;
            case DIFF_BLOCK -> Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_DIFF_BLOCK.getBooleanValue() ? Configs.Colors.SCHEMATIC_OVERLAY_COLOR_DIFF_BLOCK.getColor() : null;
            default -> null;
        };
    }

    // 和 Litematica 0.26 取模型的方式一样（没有模型的方块用它的后备模型）
    private List<BlockModelPart> getModelParts(BlockPos pos, BlockState state) {
        return LitematicaRenderer.getInstance().getWorldRenderer().getModelParts(pos, state, this.rand);
    }
}
