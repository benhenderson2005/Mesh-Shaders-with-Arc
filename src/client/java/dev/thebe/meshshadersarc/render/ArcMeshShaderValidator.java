package dev.thebe.meshshadersarc.render;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

public final class ArcMeshShaderValidator {
	private ArcMeshShaderValidator() {
	}

	public static void main(final String[] args) {
		validate("/assets/mesh-shaders-with-arc/shaders/terrain.mesh.glsl", Shaderc.shaderc_glsl_mesh_shader);
		validate(
			"/assets/mesh-shaders-with-arc/shaders/terrain.mesh.glsl",
			Shaderc.shaderc_glsl_mesh_shader,
			"ARC_TASK_CULLING",
			"1"
		);
		validate("/assets/mesh-shaders-with-arc/shaders/terrain.task.glsl", Shaderc.shaderc_glsl_task_shader);
		validate("/assets/mesh-shaders-with-arc/shaders/hzb.comp.glsl", Shaderc.shaderc_glsl_compute_shader);
		validate("/assets/mesh-shaders-with-arc/shaders/terrain_translucent.mesh.glsl", Shaderc.shaderc_glsl_mesh_shader);
		validate("/assets/mesh-shaders-with-arc/shaders/terrain.frag.glsl", Shaderc.shaderc_glsl_fragment_shader);
		System.out.println("Direct mesh, task/HZB, sorted translucent, and fragment shaders compile successfully for Vulkan 1.2");
	}

	private static void validate(final String resource, final int shaderKind, final String... macros) {
		ByteBuffer spirv = ArcShaderCompiler.compileResource(resource, shaderKind, macros);
		MemoryUtil.memFree(spirv);
	}
}
