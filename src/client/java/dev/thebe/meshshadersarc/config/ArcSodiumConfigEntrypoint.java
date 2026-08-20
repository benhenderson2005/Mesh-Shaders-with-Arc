package dev.thebe.meshshadersarc.config;

import dev.thebe.meshshadersarc.ArcMeshConfig;
import dev.thebe.meshshadersarc.MeshShadersWithArcClient;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ArcSodiumConfigEntrypoint implements ConfigEntryPoint {
	private static final Identifier DISABLE_OPTION = id("disable");
	private static final Identifier KEEP_DISTANCE_OPTION = id("keep_distance");
	private static final Identifier TEMPORAL_HZB_OPTION = id("temporal_hzb");
	private static final Identifier AUTOMATIC_MEMORY_OPTION = id("automatic_memory");
	private static final Identifier MAX_GPU_MEMORY_OPTION = id("max_gpu_memory");
	private static final Identifier PACKED_GEOMETRY_OPTION = id("packed_geometry");
	private static final Identifier SPARSE_RESIDENCY_OPTION = id("sparse_residency");
	private static final Identifier CUSTOM_TRANSLUCENCY_OPTION = id("custom_translucency");

	private static final StorageEventHandler NO_SAVE = () -> {
	};

	private final ArcSettingsStore store = ArcSettingsStore.instance();
	private final StorageEventHandler saveSettings = store::save;

	@Override
	public void registerConfigLate(final ConfigBuilder builder) {
		final var page = builder.createOptionPage()
			.setName(Component.translatable("mesh-shaders-with-arc.options.page"));

		page.addOptionGroup(builder.createOptionGroup()
			.addOption(builder.createBooleanOption(DISABLE_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.disable.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.disable.tooltip"))
				.setDefaultValue(false)
				.setBinding(ArcMeshConfig::setDisabledForSession, () -> !ArcMeshConfig.enabled())
				.setEnabled(!ArcMeshConfig.enabledSystemOverride())
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.HIGH)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(NO_SAVE)));

		page.addOptionGroup(builder.createOptionGroup()
			.setName(Component.translatable("mesh-shaders-with-arc.options.group.renderer"))
			.addOption(builder.createBooleanOption(TEMPORAL_HZB_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.temporal_hzb.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.temporal_hzb.tooltip"))
				.setDefaultValue(true)
				.setBinding(ArcMeshConfig::setTemporalHzbCullingEnabled, ArcMeshConfig::temporalHzbCullingSetting)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION) && !ArcMeshConfig.temporalHzbCullingSystemOverride(),
					DISABLE_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.HIGH)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(saveSettings))
			.addOption(builder.createBooleanOption(PACKED_GEOMETRY_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.packed_geometry.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.packed_geometry.tooltip"))
				.setDefaultValue(true)
				.setBinding(ArcMeshConfig::setPackedGeometryEnabled, ArcMeshConfig::packedGeometrySetting)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION) && !ArcMeshConfig.packedGeometrySystemOverride(),
					DISABLE_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.HIGH)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(saveSettings))
			.addOption(builder.createBooleanOption(SPARSE_RESIDENCY_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.sparse_residency.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.sparse_residency.tooltip"))
				.setDefaultValue(true)
				.setBinding(ArcMeshConfig::setSparseResidencyEnabled, ArcMeshConfig::sparseResidencySetting)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION)
						&& state.readBooleanOption(PACKED_GEOMETRY_OPTION)
						&& !ArcMeshConfig.sparseResidencySystemOverride(),
					DISABLE_OPTION, PACKED_GEOMETRY_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.MEDIUM)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(saveSettings))
			.addOption(builder.createBooleanOption(CUSTOM_TRANSLUCENCY_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.custom_translucency.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.custom_translucency.tooltip"))
				.setDefaultValue(true)
				.setBinding(ArcMeshConfig::setCustomTranslucencyEnabled, ArcMeshConfig::customTranslucencySetting)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION) && !ArcMeshConfig.customTranslucencySystemOverride(),
					DISABLE_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.MEDIUM)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(saveSettings)));

		page.addOptionGroup(builder.createOptionGroup()
			.setName(Component.translatable("mesh-shaders-with-arc.options.group.residency"))
			.addOption(builder.createIntegerOption(KEEP_DISTANCE_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.keep_distance.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.keep_distance.tooltip"))
				.setDefaultValue(ArcSettings.DEFAULT_KEEP_DISTANCE_CHUNKS)
				.setRange(
					ArcSettings.MIN_KEEP_DISTANCE_CHUNKS,
					ArcSettings.KEEP_ALL_DISTANCE_CHUNKS,
					1
				)
				.setValueFormatter(ArcSodiumConfigEntrypoint::formatKeepDistance)
				.setBinding(ArcMeshConfig::setKeepDistanceChunks, ArcMeshConfig::keepDistanceChunks)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION)
						&& state.readBooleanOption(PACKED_GEOMETRY_OPTION)
						&& !ArcMeshConfig.keepDistanceSystemOverride(),
					DISABLE_OPTION, PACKED_GEOMETRY_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.VARIES)
				.setStorageHandler(saveSettings))
			.addOption(builder.createBooleanOption(AUTOMATIC_MEMORY_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.automatic_memory.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.automatic_memory.tooltip"))
				.setDefaultValue(true)
				.setBinding(ArcMeshConfig::setAutomaticMemoryEnabled, ArcMeshConfig::automaticMemoryEnabled)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION)
						&& state.readBooleanOption(PACKED_GEOMETRY_OPTION)
						&& !ArcMeshConfig.automaticMemorySystemOverride()
						&& !ArcMeshConfig.maxGpuMemorySystemOverride(),
					DISABLE_OPTION, PACKED_GEOMETRY_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.VARIES)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(saveSettings))
			.addOption(builder.createIntegerOption(MAX_GPU_MEMORY_OPTION)
				.setName(Component.translatable("mesh-shaders-with-arc.options.max_gpu_memory.name"))
				.setTooltip(Component.translatable("mesh-shaders-with-arc.options.max_gpu_memory.tooltip"))
				.setDefaultValue(ArcSettings.DEFAULT_MAX_GPU_MEMORY_MIB)
				.setRange(
					ArcSettings.MIN_MAX_GPU_MEMORY_MIB,
					ArcSettings.MAX_MAX_GPU_MEMORY_MIB,
					ArcSettings.MAX_GPU_MEMORY_STEP_MIB
				)
				.setValueFormatter(value -> Component.translatable("mesh-shaders-with-arc.options.max_gpu_memory.value", value))
				.setBinding(ArcMeshConfig::setMaxGpuMemoryMiB, ArcMeshConfig::maxGpuMemoryMiB)
				.setEnabledProvider(
					state -> !state.readBooleanOption(DISABLE_OPTION)
						&& state.readBooleanOption(PACKED_GEOMETRY_OPTION)
						&& !state.readBooleanOption(AUTOMATIC_MEMORY_OPTION)
						&& !ArcMeshConfig.maxGpuMemorySystemOverride(),
					DISABLE_OPTION, PACKED_GEOMETRY_OPTION, AUTOMATIC_MEMORY_OPTION
				)
				.setControlHiddenWhenDisabled(false)
				.setImpact(OptionImpact.VARIES)
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.setStorageHandler(saveSettings)));

		builder.registerOwnModOptions()
			.setNonTintedIcon(id("icon.png"))
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x0071C5))
			.addPage(page);
	}

	private static Component formatKeepDistance(final int chunks) {
		if (chunks == ArcSettings.KEEP_ALL_DISTANCE_CHUNKS) {
			return Component.translatable("mesh-shaders-with-arc.options.keep_distance.all");
		}

		if (chunks == ArcSettings.MIN_KEEP_DISTANCE_CHUNKS
			|| chunks <= Minecraft.getInstance().options.getEffectiveRenderDistance()) {
			return Component.translatable("mesh-shaders-with-arc.options.keep_distance.vanilla");
		}

		return Component.translatable("mesh-shaders-with-arc.options.keep_distance.value", chunks);
	}

	private static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(MeshShadersWithArcClient.MOD_ID, path);
	}
}
