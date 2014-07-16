/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.kernel.impl.api.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.index.IndexCommandFactory;
import org.neo4j.graphdb.index.IndexImplementation;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.graphdb.index.LegacyIndexProviderTransaction;
import org.neo4j.kernel.api.LegacyIndex;
import org.neo4j.kernel.api.exceptions.legacyindex.LegacyIndexNotFoundKernelException;
import org.neo4j.kernel.impl.api.LegacyIndexApplier.ProviderLookup;
import org.neo4j.kernel.impl.index.IndexCommand;
import org.neo4j.kernel.impl.index.IndexCommand.AddNodeCommand;
import org.neo4j.kernel.impl.index.IndexCommand.AddRelationshipCommand;
import org.neo4j.kernel.impl.index.IndexCommand.CreateCommand;
import org.neo4j.kernel.impl.index.IndexCommand.DeleteCommand;
import org.neo4j.kernel.impl.index.IndexCommand.RemoveCommand;
import org.neo4j.kernel.impl.index.IndexConfigStore;
import org.neo4j.kernel.impl.index.IndexDefineCommand;
import org.neo4j.kernel.impl.index.IndexEntityType;
import org.neo4j.kernel.impl.nioneo.xa.TransactionRecordState;
import org.neo4j.kernel.impl.nioneo.xa.command.Command;

/**
 * Provides access to {@link LegacyIndex indexes}. Holds transaction state for all providers in a transaction.
 * A equivalent to TransactionRecordState, but for legacy indexes.
 *
 * @see TransactionRecordState
 */
public class LegacyIndexTransactionState implements IndexCommandFactory
{
    private final Map<String, LegacyIndexProviderTransaction> transactions = new HashMap<>();
    private final IndexConfigStore indexConfigStore;
    private final ProviderLookup providerLookup;

    // Commands
    private IndexDefineCommand defineCommand;
    private Map<String, List<IndexCommand>> nodeCommands;
    private Map<String, List<IndexCommand>> relationshipCommands;

    public LegacyIndexTransactionState( IndexConfigStore indexConfigStore, ProviderLookup providerLookup )
    {
        this.indexConfigStore = indexConfigStore;
        this.providerLookup = providerLookup;
    }

    public LegacyIndex nodeChanges( String indexName ) throws LegacyIndexNotFoundKernelException
    {
        Map<String, String> configuration = indexConfigStore.get( Node.class, indexName );
        if ( configuration == null )
        {
            throw new LegacyIndexNotFoundKernelException( "Node index '" + indexName + " not found" );
        }
        String providerName = configuration.get( IndexManager.PROVIDER );
        IndexImplementation provider = providerLookup.lookup( providerName );
        LegacyIndexProviderTransaction transaction = transactions.get( providerName );
        if ( transaction == null )
        {
            transactions.put( providerName, transaction = provider.newTransaction( this ) );
        }
        return transaction.nodeIndex( indexName, configuration );
    }

    public LegacyIndex relationshipChanges( String indexName ) throws LegacyIndexNotFoundKernelException
    {
        Map<String, String> configuration = indexConfigStore.get( Relationship.class, indexName );
        if ( configuration == null )
        {
            throw new LegacyIndexNotFoundKernelException( "Relationship index '" + indexName + " not found" );
        }
        String providerName = configuration.get( IndexManager.PROVIDER );
        IndexImplementation provider = providerLookup.lookup( providerName );
        LegacyIndexProviderTransaction transaction = transactions.get( providerName );
        if ( transaction == null )
        {
            transactions.put( providerName, transaction = provider.newTransaction( this ) );
        }
        return transaction.relationshipIndex( indexName, configuration );
    }

    public void extractCommands( List<Command> target )
    {
        if ( defineCommand != null )
        {
            target.add( defineCommand );
            extractCommands( target, nodeCommands );
            extractCommands( target, relationshipCommands );
        }

        for ( LegacyIndexProviderTransaction providerTransaction : transactions.values() )
        {
            providerTransaction.close();
        }
    }

    private void extractCommands( List<Command> target, Map<String, List<IndexCommand>> commandMap )
    {
        if ( commandMap != null )
        {
            for ( List<IndexCommand> commands : commandMap.values() )
            {
                target.addAll( commands );
            }
        }
    }

    // Methods for adding commands
    private IndexDefineCommand definitions()
    {
        if ( defineCommand == null )
        {
            defineCommand = new IndexDefineCommand();
        }
        return defineCommand;
    }

    private void addCommand( String indexName, IndexCommand command )
    {
        addCommand( indexName, command, false );
    }

    private void addCommand( String indexName, IndexCommand command, boolean clearFirst )
    {
        List<IndexCommand> commands = null;
        if ( command.getEntityType() == IndexEntityType.Node.id() )
        {
            if ( nodeCommands == null )
            {
                nodeCommands = new HashMap<>();
            }
            commands = nodeCommands.get( indexName );
            if ( commands == null )
            {
                nodeCommands.put( indexName, commands = new ArrayList<>() );
            }
        }
        else if ( command.getEntityType() == IndexEntityType.Relationship.id() )
        {
            if ( nodeCommands == null )
            {
                nodeCommands = new HashMap<>();
            }
            commands = nodeCommands.get( indexName );
            if ( commands == null )
            {
                nodeCommands.put( indexName, commands = new ArrayList<>() );
            }
        }
        else
        {
            throw new IllegalArgumentException( "" + command.getEntityType() );
        }

        if ( clearFirst )
        {
            commands.clear();
        }

        commands.add( command );
    }

    @Override
    public void addNode( String indexName, long id, String key, Object value )
    {
        AddNodeCommand command = new AddNodeCommand();
        command.init( definitions().getOrAssignIndexNameId( indexName ),
                id, definitions().getOrAssignKeyId( key ), value );
        addCommand( indexName, command );
    }

    @Override
    public void addRelationship( String indexName, long id, String key, Object value,
            long startNode, long endNode )
    {
        AddRelationshipCommand command = new AddRelationshipCommand();
        command.init( definitions().getOrAssignIndexNameId( indexName ),
                id, definitions().getOrAssignKeyId( key ), value, startNode, endNode );
        addCommand( indexName, command );
    }

    @Override
    public void removeNode( String indexName, long id,
            String keyOrNull, Object valueOrNull )
    {
        RemoveCommand command = new RemoveCommand();
        command.init( definitions().getOrAssignIndexNameId( indexName ),
                IndexEntityType.Node.id(), id, definitions().getOrAssignKeyId( keyOrNull ), valueOrNull );
        addCommand( indexName, command );
    }

    @Override
    public void removeRelationship( String indexName, long id,
            String keyOrNull, Object valueOrNull )
    {
        RemoveCommand command = new RemoveCommand();
        command.init( definitions().getOrAssignIndexNameId( indexName ),
                IndexEntityType.Relationship.id(), id, definitions().getOrAssignKeyId( keyOrNull ), valueOrNull );
        addCommand( indexName, command );
    }

    public void deleteIndex( IndexEntityType entityType, String indexName )
    {
        DeleteCommand command = new DeleteCommand();
        command.init( definitions().getOrAssignIndexNameId( indexName ), entityType.id() );
        addCommand( indexName, command, true );
    }

    public void createIndex( IndexEntityType entityType, String indexName, Map<String, String> config )
    {
        CreateCommand command = new CreateCommand();
        command.init( definitions().getOrAssignIndexNameId( indexName ), entityType.id(), config );
        addCommand( indexName, command );
    }

    public boolean isReadOnly()
    {
        return defineCommand == null;
    }

    /** Set this datastructure to it's initial state, allowing it to be re-used as if it had just been new'ed up. */
    public LegacyIndexTransactionState initialize()
    {
        transactions.clear();
        defineCommand = null;
        nodeCommands = null;
        relationshipCommands = null;
        return this;
    }
}
