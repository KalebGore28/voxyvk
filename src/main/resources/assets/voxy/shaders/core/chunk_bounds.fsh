#version 450

//Depth only (see chunk_bounds.vsh). The pipeline's one colour target is never written.
layout(early_fragment_tests) in;

void main() {}
