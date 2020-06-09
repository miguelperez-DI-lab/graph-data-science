/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.graphalgo.impl.betweenness;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.LongObjectScatterMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.RelationshipIterator;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeAtomicDoubleArray;
import org.neo4j.graphalgo.core.utils.paged.HugeIntArray;
import org.neo4j.graphalgo.core.utils.paged.HugeLongArrayQueue;
import org.neo4j.graphalgo.core.utils.paged.HugeLongDoubleMap;
import org.neo4j.graphalgo.core.utils.paged.HugeLongLongMap;
import org.neo4j.graphalgo.core.utils.paged.PagedLongStack;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Randomized Approximate Brandes. See https://arxiv.org/pdf/1702.06087.pdf.
 *
 * The implementation follows the same approach as {@link BetweennessCentrality}
 * with an additional node filter to select interesting nodes. the result is multiplied
 * with a factor which is based on the probability of which the filter accepts nodes.
 *
 * There is a significant performance drop if the direction is BOOTH. Its more efficient
 * to load the graph as undirected and do the
 */
public class RABrandesBetweennessCentrality extends Algorithm<RABrandesBetweennessCentrality, RABrandesBetweennessCentrality> {

    public interface SelectionStrategy {

        /**
         * node id filter
         * @return true if the nodes is accepted, false otherwise
         */
        boolean select(long nodeId);

        /**
         * count of selectable nodes
         */
        long size();
    }

    private final Graph graph;
    private volatile AtomicLong nodeQueue = new AtomicLong();
    private HugeAtomicDoubleArray centrality;
    private final long nodeCount;
    private final long expectedNodeCount;
    private final double directionFactor;
    private SelectionStrategy selectionStrategy;

    private final ExecutorService executorService;
    private final int concurrency;
    private final AllocationTracker tracker;

    public RABrandesBetweennessCentrality(
        Graph graph,
        SelectionStrategy selectionStrategy,
        ExecutorService executorService,
        int concurrency,
        AllocationTracker tracker
    ) {
        this(graph, selectionStrategy, false, executorService, concurrency, tracker);
    }

    public RABrandesBetweennessCentrality(
        Graph graph,
        SelectionStrategy selectionStrategy,
        boolean undirected,
        ExecutorService executorService,
        int concurrency,
        AllocationTracker tracker
    ) {
        this.graph = graph;
        this.executorService = executorService;
        this.concurrency = concurrency;
        this.nodeCount = graph.nodeCount();
        this.centrality = HugeAtomicDoubleArray.newArray(nodeCount, tracker);
        this.selectionStrategy = selectionStrategy;
        this.expectedNodeCount = selectionStrategy.size();
        this.directionFactor = undirected
            ? (nodeCount * 2.0) / selectionStrategy.size()
            : (nodeCount * 1.0) / selectionStrategy.size();
        this.tracker = tracker;
    }

    /**
     * compute centrality
     *
     * @return itself for method chaining
     */
    @Override
    public RABrandesBetweennessCentrality compute() {
        nodeQueue.set(0);
        ArrayList<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executorService.submit(new BCTask(tracker)));
        }
        ParallelUtil.awaitTermination(futures);
        return this;
    }

    /**
     * get the centrality array
     *
     * @return array with centrality
     */
    public HugeAtomicDoubleArray getCentrality() {
        return centrality;
    }

    /**
     * emit the result stream
     *
     * @return stream if Results
     */
    public Stream<BetweennessCentrality.Result> resultStream() {
        return LongStream
            .range(0, nodeCount)
            .mapToObj(nodeId -> new BetweennessCentrality.Result(
                graph.toOriginalNodeId(nodeId),
                centrality.get(nodeId)
            ));
    }

    @Override
    public RABrandesBetweennessCentrality me() {
        return this;
    }

    /**
     * release inner data structures
     */
    @Override
    public void release() {
        selectionStrategy = null;
    }

    /**
     * a BCTask takes one element from the nodeQueue as long as
     * it is lower then nodeCount and calculates it's centrality
     */
    private class BCTask implements Runnable {

        private final RelationshipIterator localRelationshipIterator;
        // we have to keep all paths during eval (memory intensive)
        private final LongObjectMap<LongArrayList> paths;
        /**
         * contains nodes which have been visited during the first round
         */
        private final PagedLongStack backwardNodes;
        /**
         * the queue contains 2 elements per node. the node itself
         * and its depth. Both values are pushed or taken from the
         * stack during the evaluation as pair.
         */
        private final HugeLongArrayQueue forwardNodes;
        private final HugeLongDoubleMap delta;
        private final HugeLongLongMap sigma;
        private final HugeIntArray distance;

        private BCTask(AllocationTracker tracker) {
            this.localRelationshipIterator = graph.concurrentCopy();

            // TODO: replace with AdjacencyList or write own PagedLongObjectScatterMap
            // Note that this depends on the max-in-degree which is Int.MAX in Neo4j, so maybe no need to change?
            this.paths = new LongObjectScatterMap<>((int) expectedNodeCount);
            this.backwardNodes = new PagedLongStack(nodeCount, tracker);
            // TODO: make queue growable
            this.forwardNodes = HugeLongArrayQueue.newQueue(nodeCount, tracker);
            // TODO: benchmark maps vs arrays
            this.sigma = new HugeLongLongMap(expectedNodeCount, tracker);
            this.delta = new HugeLongDoubleMap(expectedNodeCount, tracker);

            this.distance = HugeIntArray.newArray(nodeCount, tracker);
        }

        @Override
        public void run() {
            for (;;) {
                // take start node from the queue
                long startNodeId = nodeQueue.getAndIncrement();
                if (startNodeId >= nodeCount || !running()) {
                    return;
                }
                // check whether the node is part of the subset
                if (!selectionStrategy.select(startNodeId)) {
                    continue;
                }
                // reset
                getProgressLogger().logProgress((double) startNodeId / (nodeCount - 1));

                distance.fill(-1);
                sigma.clear();
                paths.clear();
                delta.clear();

                sigma.addTo(startNodeId, 1);
                distance.set(startNodeId, 0);

                forwardNodes.add(startNodeId);

                // BC forward traversal
                while (!forwardNodes.isEmpty()) {
                    long node = forwardNodes.remove();
                    backwardNodes.push(node);
                    int distanceNode = distance.get(node);

                    localRelationshipIterator.forEachRelationship(node, (source, target) -> {
                        if (distance.get(target) < 0) {
                            forwardNodes.add(target);
                            distance.set(target, distanceNode + 1);
                        }

                        if (distance.get(target) == distanceNode + 1) {
                            // TODO: consider moving this out of the lambda (benchmark)
                            long sigmaNode = sigma.getOrDefault(node, 0);
                            sigma.addTo(target, sigmaNode);
                            append(target, node);
                        }
                        return true;
                    });
                }

                while (!backwardNodes.isEmpty()) {
                    long node = backwardNodes.pop();
                    LongArrayList predecessors = paths.get(node);

                    double dependencyNode = delta.getOrDefault(node, 0);
                    double sigmaNode = sigma.getOrDefault(node, 0);

                    if (null != predecessors) {
                        predecessors.forEach((Consumer<? super LongCursor>) predecessor -> {
                            double sigmaPredecessor = sigma.getOrDefault(predecessor.value, 0);
                            double dependency = sigmaPredecessor / sigmaNode * (dependencyNode + 1.0);
                            delta.addTo(predecessor.value, dependency);
                        });
                    }
                    if (node != startNodeId) {
                        // TODO: replace with + (creates 2! objects per call due to reference to outer scopes)
                        centrality.update(node, (value) -> value + directionFactor * dependencyNode);
                    }
                }
            }
        }

        // append node to the path at target
        private void append(long target, long node) {
            LongArrayList predecessors = paths.get(target);
            if (null == predecessors) {
                predecessors = new LongArrayList();
                paths.put(target, predecessors);
            }
            predecessors.add(node);
        }
    }
}
