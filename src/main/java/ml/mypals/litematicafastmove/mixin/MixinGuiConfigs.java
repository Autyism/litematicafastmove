package ml.mypals.litematicafastmove.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiConfigs;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.util.StringUtils;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

// 本模组配置显示在 Litematica 配置界面的 Generic 页 (以及 All 页) 末尾
@Mixin(value = GuiConfigs.class, remap = false)
public abstract class MixinGuiConfigs {

    @ModifyReturnValue(method = "getConfigs", at = @At("RETURN"))
    private List<ConfigOptionWrapper> appendFastMoveConfigs(List<ConfigOptionWrapper> configs) {
        GuiConfigs.ConfigGuiTab tab = DataManager.getConfigGuiTab();
        if (tab != GuiConfigs.ConfigGuiTab.GENERIC && tab != GuiConfigs.ConfigGuiTab.ALL) return configs;

        List<ConfigOptionWrapper> result = new ArrayList<>(configs);
        result.add(new ConfigOptionWrapper(StringUtils.translate("litematicafastmove.config.category")));
        result.addAll(ConfigOptionWrapper.createFor(FastMoveConfig.OPTIONS));
        return result;
    }
}
