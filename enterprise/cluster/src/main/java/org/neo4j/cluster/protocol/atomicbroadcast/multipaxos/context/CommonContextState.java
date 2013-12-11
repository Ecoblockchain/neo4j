/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cluster.protocol.atomicbroadcast.multipaxos.context;

import java.net.URI;

import org.neo4j.cluster.protocol.cluster.ClusterConfiguration;

class CommonContextState
{
    private URI boundAt;
    private long lastKnownLearnedInstanceInCluster = -1;
    private long nextInstanceId = 0;
    private ClusterConfiguration configuration;

    public CommonContextState( ClusterConfiguration configuration )
    {
        this.configuration = configuration;
    }

    public URI boundAt()
    {
        return boundAt;
    }

    public void setBoundAt( URI boundAt )
    {
        this.boundAt = boundAt;
    }

    public long lastKnownLearnedInstanceInCluster()
    {
        return lastKnownLearnedInstanceInCluster;
    }

    public void setLastKnownLearnedInstanceInCluster( long lastKnownLearnedInstanceInCluster )
    {
        this.lastKnownLearnedInstanceInCluster = lastKnownLearnedInstanceInCluster;
    }

    public long nextInstanceId()
    {
        return nextInstanceId;
    }

    public void setNextInstanceId( long nextInstanceId )
    {
        this.nextInstanceId = nextInstanceId;
    }

    public long getAndIncrementInstanceId()
    {
        return nextInstanceId++;
    }

    public ClusterConfiguration configuration()
    {
        return configuration;
    }

    public void setConfiguration( ClusterConfiguration configuration )
    {
        this.configuration = configuration;
    }
}
