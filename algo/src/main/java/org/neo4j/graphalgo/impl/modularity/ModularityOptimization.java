/*
 * Copyright (c) 2017-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.impl.modularity;

import com.carrotsearch.hppc.BitSet;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.ParallelUtil;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeAtomicDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArray;
import org.neo4j.graphalgo.impl.coloring.K1Coloring;
import org.neo4j.graphdb.Direction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.stream.LongStream;

/**
 * Implementation of parallel modularity optimization based on:
 *
 * Lu, Hao, Mahantesh Halappanavar, and Ananth Kalyanaraman.
 * "Parallel heuristics for scalable community detection."
 * Parallel Computing 47 (2015): 19-37.
 * https://arxiv.org/pdf/1410.1237.pdf
 */
public final class ModularityOptimization extends Algorithm<ModularityOptimization> {

    private final int concurrency;
    private final int maxIterations;
    private final long nodeCount;
    private final long batchSize;
    private final Graph graph;
    private final Direction direction;
    private final HugeLongArray currentCommunities;
    private final HugeLongArray nextCommunities;
    private final HugeDoubleArray cumulativeNodeWeights;
    private final HugeDoubleArray nodeCommunityInfluences;
    private final HugeAtomicDoubleArray communityWeights;
    private final ExecutorService executor;
    private final AllocationTracker tracker;

    private int iterationCounter;
    private double totalNodeWeight = 0.0;
    private double modularity = -1.0;
    private BitSet colorsUsed;
    private HugeLongArray colors;
    private HugeAtomicDoubleArray communityWeightUpdates;

    ModularityOptimization(
        final Graph graph,
        Direction direction,
        int maxIterations, final int concurrency,
        final int minBatchSize,
        final ExecutorService executor,
        final AllocationTracker tracker
    ) {
        this.graph = graph;
        this.nodeCount = graph.nodeCount();
        this.direction = direction;
        this.maxIterations = maxIterations;
        this.executor = executor;
        this.concurrency = concurrency;
        this.tracker = tracker;
        this.currentCommunities = HugeLongArray.newArray(nodeCount, tracker);
        this.nextCommunities = HugeLongArray.newArray(nodeCount, tracker);
        this.cumulativeNodeWeights = HugeDoubleArray.newArray(nodeCount, tracker);
        this.nodeCommunityInfluences = HugeDoubleArray.newArray(nodeCount, tracker);
        this.communityWeights = HugeAtomicDoubleArray.newArray(nodeCount, tracker);
        this.communityWeightUpdates = HugeAtomicDoubleArray.newArray(nodeCount, tracker);
        this.batchSize = ParallelUtil.adjustedBatchSize(
            nodeCount,
            concurrency,
            minBatchSize,
            Integer.MAX_VALUE
        );
    }

    public ModularityOptimization compute() {
        init();
        computeColoring();

        for(iterationCounter = 0; iterationCounter < maxIterations; iterationCounter++) {
            nodeCommunityInfluences.fill(0.0);

            long currentColor = colorsUsed.nextSetBit(0);
            while(currentColor != -1) {
                optimizeForColor(currentColor);
                currentColor = colorsUsed.nextSetBit(currentColor + 1);
            }

            if (!updateModularity()) break;
        }

        return this;
    }

    private void init() {
        double doubleTotalNodeWeight = ParallelUtil.parallelStream(
            LongStream.range(0, nodeCount),
            (nodeStream) ->
                nodeStream.mapToDouble((nodeId) -> {
                    // Note: this map function has side effects - For performance reasons!!!11!
                    currentCommunities.set(nodeId, nodeId);

                    MutableDouble cumulativeWeight = new MutableDouble(0.0D);

                    graph.concurrentCopy().forEachRelationship(nodeId, direction, 1.0, (s, t, w) -> {
                        cumulativeWeight.add(w);
                        return true;
                    });

                    communityWeights.set(nodeId, cumulativeWeight.doubleValue());

                    cumulativeNodeWeights.set(nodeId, cumulativeWeight.doubleValue());

                    return cumulativeWeight.doubleValue();
                }).reduce(Double::sum)
                    .orElseThrow(() -> new RuntimeException("Error initializing modularity optimization."))
        );

        totalNodeWeight = doubleTotalNodeWeight / 2.0;
        currentCommunities.copyTo(nextCommunities, nodeCount);
    }

    private void computeColoring() {
        K1Coloring coloring = new K1Coloring(
            graph,
            direction,
            10,
            (int) batchSize,
            concurrency,
            executor,
            tracker
        ).compute();

        this.colors = coloring.colors();
        this.colorsUsed = coloring.usedColors();
    }

    private void optimizeForColor(long currentColor) {
        // run optimization tasks for every node
        ParallelUtil.runWithConcurrency(
            concurrency,
            createModularityOptimizationTasks(currentColor),
            executor
        );

        // swap old and new communities
        nextCommunities.copyTo(currentCommunities, nodeCount);

        // apply communityWeight updates to communityWeights
        ParallelUtil.parallelStreamConsume(
            LongStream.range(0, nodeCount),
            (stream -> stream.forEach((nodeId) -> {
                final double update = communityWeightUpdates.get(nodeId);
                communityWeights.update(nodeId, (w) -> w + update);
            }))
        );

        // reset communityWeightUpdates
        communityWeightUpdates = HugeAtomicDoubleArray.newArray(nodeCount, (i) -> 0.0, tracker);
    }

    private Collection<ModularityOptimizationTask> createModularityOptimizationTasks(long currentColor) {
        final Collection<ModularityOptimizationTask> tasks = new ArrayList<>(concurrency);
        for (long i = 0L; i < this.nodeCount; i += batchSize) {
            tasks.add(
                new ModularityOptimizationTask(
                    graph,
                    i,
                    Math.min(i + batchSize, nodeCount),
                    currentColor,
                    totalNodeWeight,
                    direction,
                    colors,
                    currentCommunities,
                    nextCommunities,
                    cumulativeNodeWeights,
                    nodeCommunityInfluences,
                    communityWeights,
                    communityWeightUpdates
                )
            );
        }
        return tasks;
    }

    private boolean updateModularity() {
        double oldModularity = this.modularity;
        this.modularity = calculateModularity();

        return this.modularity > oldModularity && Math.abs(this.modularity - oldModularity) > 0.001;
    }

    private double calculateModularity() {
        double ex = ParallelUtil.parallelStream(
            LongStream.range(0, nodeCount),
            (nodeStream) ->
                nodeStream
                    .mapToDouble(nodeCommunityInfluences::get)
                    .reduce(Double::sum)
                    .orElseThrow(() -> new RuntimeException("Error while comptuing modularity"))
        );

        double ax = ParallelUtil.parallelStream(
            LongStream.range(0, nodeCount),
            (nodeStream) ->
                nodeStream
                    .mapToDouble((nodeId) -> Math.pow(communityWeights.get(nodeId), 2.0))
                    .reduce(Double::sum)
                    .orElseThrow(() -> new RuntimeException("Error while comptuing modularity"))
        );

        return (ex / (2 * totalNodeWeight)) - (ax / (Math.pow(2 * totalNodeWeight, 2)));
    }


    @Override
    public ModularityOptimization me() {
        return this;
    }

    @Override
    public void release() {
        this.nextCommunities.release();
        this.communityWeights.release();
        this.communityWeightUpdates.release();
        this.cumulativeNodeWeights.release();
        this.nodeCommunityInfluences.release();
        this.colors.release();
        this.colorsUsed = null;
    }

    public HugeLongArray getCommunityIds() {
        return this.currentCommunities;
    }

    public int getIterations() {
        return this.iterationCounter;
    }

    public double getModularity() {
        return this.modularity;
    }
}
