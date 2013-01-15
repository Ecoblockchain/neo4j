/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.api;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.api.KernelAPI;
import org.neo4j.kernel.api.TransactionContext;
import org.neo4j.kernel.impl.core.PropertyIndexManager;

public class GDBackedKernel implements KernelAPI
{
    private final PropertyIndexManager propertyIndexManager;

    public GDBackedKernel( GraphDatabaseAPI db )
    {
        this( resolve(db, PropertyIndexManager.class ) );
    }

    public GDBackedKernel( PropertyIndexManager propertyIndexManager )
    {
        this.propertyIndexManager = propertyIndexManager;
    }

    private static <T> T resolve( GraphDatabaseAPI gdb, Class<T> cls)
    {
        DependencyResolver dependencyResolver = gdb.getDependencyResolver();
        return dependencyResolver.resolveDependency( cls );
    }

    @Override
    public TransactionContext newTransactionContext()
    {
        return new GDBackedTransactionContext( propertyIndexManager );
    }
}
