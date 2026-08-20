package dev.thebe.meshshadersarc;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MeshShadersWithArcClient implements ClientModInitializer {
	public static final String MOD_ID = "mesh-shaders-with-arc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		if (ArcMeshConfig.enabled()) {
			LOGGER.info("Mesh Shaders with Arc is enabled; waiting for a compatible Vulkan device");
		} else {
			LOGGER.info("Mesh Shaders with Arc is disabled by system property");
		}
	}
}
