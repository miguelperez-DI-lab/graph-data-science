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
package org.neo4j.graphalgo.core.loading;

import org.neo4j.graphalgo.NodeLabel;
import org.neo4j.graphalgo.PropertyMapping;
import org.neo4j.graphalgo.annotation.ValueClass;
import org.neo4j.graphalgo.api.NodeMapping;
import org.neo4j.graphalgo.api.NodeProperties;

import java.util.Map;

// TODO: rename to `NodeMappingAndProperties`
@ValueClass
public interface IdsAndProperties {

    NodeMapping idMap();

    Map<NodeLabel, Map<PropertyMapping, NodeProperties>> properties();

    static IdsAndProperties of(
        NodeMapping nodeMapping,
        Map<NodeLabel, Map<PropertyMapping, NodeProperties>> properties
    ) {
        return ImmutableIdsAndProperties.of(nodeMapping, properties);
    }
}
