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
package org.neo4j.gds.core.pagecached;

import org.neo4j.graphalgo.core.huge.AdjacencyList;
import org.neo4j.graphalgo.core.loading.MutableIntValue;
import org.neo4j.io.pagecache.PageCursor;

import java.io.IOException;
import java.util.Arrays;

import static org.neo4j.gds.core.pagecached.VarLongDecoding.decodeDeltaVLongs;

final class AdjacencyDecompressingReader {

    private static final int CHUNK_SIZE = 64;

    private final long[] block;
    private int pos;
    private PageCursor cursor;
    private int offset;

    AdjacencyDecompressingReader() {
        this.block = new long[CHUNK_SIZE];
    }

    void copyFrom(AdjacencyDecompressingReader other) {
        System.arraycopy(other.block, 0, block, 0, CHUNK_SIZE);
        pos = other.pos;
        cursor = other.cursor;
        offset = other.offset;
    }

    int reset(PageCursor pageCursor, int offset) throws IOException {
        this.cursor = pageCursor;
        int numAdjacencies = pageCursor.getInt(offset); // offset should not be 0
        this.offset = decodeDeltaVLongs(
            0L,
            pageCursor,
            Integer.BYTES + offset,
            Math.min(numAdjacencies, CHUNK_SIZE),
            block
        );
        pos = 0;
        return numAdjacencies;
    }

    long next(int remaining) throws IOException {
        int pos = this.pos++;
        if (pos < CHUNK_SIZE) {
            return block[pos];
        }
        return readNextBlock(remaining);
    }

    private long readNextBlock(int remaining) throws IOException {
        pos = 1;
        offset = decodeDeltaVLongs(block[CHUNK_SIZE - 1], cursor, offset, Math.min(remaining, CHUNK_SIZE), block);
        return block[0];
    }

    long skipUntil(long target, int remaining, MutableIntValue consumed) throws IOException {
        int pos = this.pos;
        long[] block = this.block;
        int available = remaining;

        // skip blocks until we have either not enough available to decode or have advanced far enough
        while (available > CHUNK_SIZE - pos && block[CHUNK_SIZE - 1] <= target) {
            int skippedInThisBlock = CHUNK_SIZE - pos;
            int needToDecode = Math.min(CHUNK_SIZE, available - skippedInThisBlock);
            offset = decodeDeltaVLongs(block[CHUNK_SIZE - 1], cursor, offset, needToDecode, block);
            available -= skippedInThisBlock;
            pos = 0;
        }

        // last block
        if (available <= 0) {
            return AdjacencyList.DecompressingCursor.NOT_FOUND;
        }

        int targetPos = findPosStrictlyGreaterInBlock(target, pos, Math.min(pos + available, CHUNK_SIZE), block);
        // we need to consume including targetPos, not to it, therefore +1
        available -= (1 + targetPos - pos);
        consumed.value = remaining - available;
        this.pos = 1 + targetPos;
        return block[targetPos];
    }

    long advance(long target, int remaining, MutableIntValue consumed) throws IOException {
        int pos = this.pos;
        long[] block = this.block;
        int available = remaining;

        // skip blocks until we have either not enough available to decode or have advanced far enough
        while (available > CHUNK_SIZE - pos && block[CHUNK_SIZE - 1] < target) {
            int skippedInThisBlock = CHUNK_SIZE - pos;
            int needToDecode = Math.min(CHUNK_SIZE, available - skippedInThisBlock);
            offset = decodeDeltaVLongs(block[CHUNK_SIZE - 1], cursor, offset, needToDecode, block);
            available -= skippedInThisBlock;
            pos = 0;
        }

        // last block
        int targetPos = findPosInBlock(target, pos, Math.min(pos + available, CHUNK_SIZE), block);
        // we need to consume including targetPos, not to it, therefore +1
        available -= (1 + targetPos - pos);
        consumed.value = remaining - available;
        this.pos = 1 + targetPos;
        return block[targetPos];
    }

    private int findPosStrictlyGreaterInBlock(long target, int pos, int limit, long[] block) {
        return findPosInBlock(1L + target, pos, limit, block);
    }

    private int findPosInBlock(long target, int pos, int limit, long[] block) {
        int targetPos = Arrays.binarySearch(block, pos, limit, target);
        if (targetPos < 0) {
            targetPos = Math.min(-1 - targetPos, -1 + limit);
        }
        return targetPos;
    }
}