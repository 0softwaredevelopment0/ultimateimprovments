package com.ultimateimprovments.space;

import org.bukkit.generator.ChunkGenerator;

/**
 * Produces completely empty (void) chunks for the space dimension.
 * <p>
 * An empty generator is expressed by disabling the vanilla generation stages
 * below; disabling surface generation also prevents bedrock placement.
 */
public class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() { return false; }

    @Override
    public boolean shouldGenerateSurface() { return false; }

    @Override
    public boolean shouldGenerateCaves() { return false; }

    @Override
    public boolean shouldGenerateDecorations() { return false; }

    @Override
    public boolean shouldGenerateMobs() { return false; }

    @Override
    public boolean shouldGenerateStructures() { return false; }
}
