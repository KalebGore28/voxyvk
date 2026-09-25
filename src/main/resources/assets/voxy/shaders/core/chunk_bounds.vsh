#version 450

//Voxy chunk bounds, Vulkan path (Blaze3D pipeline voxy:pipeline/chunk_bounds, see
// Blaze3DBoundRenderer): one 36-index box per Sodium-visible chunk section, drawn with a
// "further" depth test so the depth-bound target keeps the far side of the volume vanilla
// terrain covers. The terrain shaders discard LOD fragments behind it.
//Same math as chunkoutline/outline.vsh (the OpenGL path's shader), in Minecraft's shader
// format: Blaze3D binds the uniform block and the vertex input by name, and has no storage
// buffers, so the packed section position is a per-instance vertex attribute.
//CLOSER_SIGN is a pipeline define: +1 with reverse-Z, -1 otherwise (util/depthutils.glsl).

layout(std140) uniform VoxyChunkBounds {
    mat4 MVP;//translated by the camera's position inside its block
    ivec4 CameraBlockPos;
    vec4 InnerBlockAndRadius;//xyz: camera position inside its block, w: render distance in blocks
};

in ivec2 ChunkPos;//per instance: the section position packed as in IBoundStore

ivec3 unpackPos(ivec2 pos) {
    return ivec3(pos.y>>10, (pos.x<<12)>>12, ((pos.y<<22)|int(uint(pos.x)>>10))>>10);
}

bool shouldRender(ivec3 icorner) {
    vec3 corner = vec3(mix(mix(ivec3(0), icorner-1, greaterThan(icorner-1, ivec3(0))), icorner+17, lessThan(icorner+17, ivec3(0))))-InnerBlockAndRadius.xyz;
    bool visible = (corner.x*corner.x + corner.z*corner.z) < (InnerBlockAndRadius.w*InnerBlockAndRadius.w);
    visible = visible && abs(corner.y) < InnerBlockAndRadius.w;
    return visible;
}

void main() {
    ivec3 origin = unpackPos(ChunkPos)*16;
    origin -= CameraBlockPos.xyz;

    if (!shouldRender(origin)) {
        gl_Position = vec4(-100.0f, -100.0f, -100.0f, 0.0f);
        return;
    }

    //The index buffer walks the 8 box corners: vertex index bits are x, z, y
    ivec3 cubeCornerI = ivec3(gl_VertexIndex&1, (gl_VertexIndex>>2)&1, (gl_VertexIndex>>1)&1)*16;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin), 1);
    gl_Position.z += CLOSER_SIGN*0.0005f;//Bring closer to camera
}
