package dev.thebe.meshshadersarc.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

final class ArcShaderCompiler {
	private ArcShaderCompiler() {
	}

	static ByteBuffer compileResource(final String resource, final int shaderKind) {
		return compileResource(resource, shaderKind, new String[0]);
	}

	static ByteBuffer compileResource(final String resource, final int shaderKind, final String... macroDefinitions) {
		if ((macroDefinitions.length & 1) != 0) {
			throw new IllegalArgumentException("Shader macro definitions must be name/value pairs");
		}

		String source = readResource(resource);
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		if (compiler == MemoryUtil.NULL || options == MemoryUtil.NULL) {
			if (options != MemoryUtil.NULL) {
				Shaderc.shaderc_compile_options_release(options);
			}
			if (compiler != MemoryUtil.NULL) {
				Shaderc.shaderc_compiler_release(compiler);
			}
			throw new IllegalStateException("Unable to initialize shaderc");
		}

		ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
		ByteBuffer filename = MemoryUtil.memUTF8(resource);
		ByteBuffer entryPoint = MemoryUtil.memUTF8("main");
		long result = MemoryUtil.NULL;
		try {
			Shaderc.shaderc_compile_options_set_target_env(
				options,
				Shaderc.shaderc_target_env_vulkan,
				Shaderc.shaderc_env_version_vulkan_1_2
			);
			for (int i = 0; i < macroDefinitions.length; i += 2) {
				Shaderc.shaderc_compile_options_add_macro_definition(options, macroDefinitions[i], macroDefinitions[i + 1]);
			}
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			result = Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, shaderKind, filename, entryPoint, options);
			if (result == MemoryUtil.NULL) {
				throw new IllegalStateException("shaderc returned no result for " + resource);
			}

			if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
				throw new IllegalStateException("Failed to compile " + resource + ": " + Shaderc.shaderc_result_get_error_message(result));
			}

			ByteBuffer resultBytes = Shaderc.shaderc_result_get_bytes(result);
			ByteBuffer copy = MemoryUtil.memAlloc(resultBytes.remaining());
			copy.put(resultBytes).flip();
			return copy;
		} finally {
			if (result != MemoryUtil.NULL) {
				Shaderc.shaderc_result_release(result);
			}
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
			MemoryUtil.memFree(entryPoint);
			MemoryUtil.memFree(filename);
			MemoryUtil.memFree(sourceBuffer);
		}
	}

	private static String readResource(final String path) {
		try (InputStream stream = ArcShaderCompiler.class.getResourceAsStream(path)) {
			if (stream == null) {
				throw new IllegalStateException("Missing shader resource " + path);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to read shader resource " + path, exception);
		}
	}
}
