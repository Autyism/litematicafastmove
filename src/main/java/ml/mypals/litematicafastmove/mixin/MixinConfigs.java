package ml.mypals.litematicafastmove.mixin;

import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.malilib.config.ConfigUtils;
import ml.mypals.litematicafastmove.config.FastMoveConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 本模组配置随 Litematica 一起读写，保存在 litematica.json 的 FastMove 分类下
@Mixin(value = Configs.class, remap = false)
public abstract class MixinConfigs {

    @Inject(method = "loadFromFile", at = @At(value = "INVOKE", ordinal = 4, shift = At.Shift.AFTER,
            target = "Lfi/dy/masa/malilib/config/ConfigUtils;readConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"))
    private static void loadFastMoveConfigs(CallbackInfo ci, @Local JsonObject root) {
        ConfigUtils.readConfigBase(root, FastMoveConfig.CATEGORY, FastMoveConfig.OPTIONS);
    }

    @Inject(method = "saveToFile", at = @At(value = "INVOKE", ordinal = 4, shift = At.Shift.AFTER,
            target = "Lfi/dy/masa/malilib/config/ConfigUtils;writeConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"))
    private static void saveFastMoveConfigs(CallbackInfo ci, @Local JsonObject root) {
        ConfigUtils.writeConfigBase(root, FastMoveConfig.CATEGORY, FastMoveConfig.OPTIONS);
    }
}
