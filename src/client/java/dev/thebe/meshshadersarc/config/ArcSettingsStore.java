package dev.thebe.meshshadersarc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.thebe.meshshadersarc.MeshShadersWithArcClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ArcSettingsStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "mesh-shaders-with-arc.json";
	private static final ArcSettingsStore INSTANCE = new ArcSettingsStore();

	private final Path path;
	private final ArcSettings settings;

	private ArcSettingsStore() {
		path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		settings = load(path);
	}

	public static ArcSettingsStore instance() {
		return INSTANCE;
	}

	public ArcSettings settings() {
		return settings;
	}

	public synchronized void save() {
		Path temporary = null;
		try {
			final Path directory = path.getParent();
			Files.createDirectories(directory);
			temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
			Files.writeString(temporary, GSON.toJson(settings), StandardCharsets.UTF_8);

			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			MeshShadersWithArcClient.LOGGER.error("Could not save Arc settings to {}", path, exception);
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException cleanupException) {
					MeshShadersWithArcClient.LOGGER.debug("Could not remove temporary Arc settings file {}", temporary, cleanupException);
				}
			}
		}
	}

	private static ArcSettings load(final Path path) {
		if (!Files.isRegularFile(path)) {
			return new ArcSettings();
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			final ArcSettings loaded = GSON.fromJson(reader, ArcSettings.class);
			if (loaded == null) {
				throw new JsonParseException("settings document was null");
			}
			loaded.normalize();
			return loaded;
		} catch (IOException | JsonParseException exception) {
			MeshShadersWithArcClient.LOGGER.error("Could not load Arc settings from {}; using defaults", path, exception);
			return new ArcSettings();
		}
	}
}
