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
package org.neo4j.kernel.impl.store.impl;

import java.io.File;
import java.io.IOException;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.impl.nioneo.store.InvalidRecordException;
import org.neo4j.kernel.impl.nioneo.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.Store;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

import static org.neo4j.io.pagecache.PagedFile.PF_EXCLUSIVE_LOCK;
import static org.neo4j.io.pagecache.PagedFile.PF_NO_GROW;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_LOCK;

public class StandardStore<RECORD, CURSOR extends Store.RecordCursor> extends LifecycleAdapter
             implements Store<RECORD, CURSOR>
{
    private final StoreFormat<RECORD, CURSOR> storeFormat;
    private final StoreFormat.RecordFormat<RECORD> recordFormat;

    private final StoreIdGenerator idGenerator;
    private final PageCache pageCache;
    private final FileSystemAbstraction fs;
    private final File dbFileName;

    private StoreToolkit toolkit;
    private PagedFile file;

    public StandardStore(StoreFormat<RECORD, CURSOR> format, File baseFileName, StoreIdGenerator idGenerator,
                         PageCache pageCache, FileSystemAbstraction fs )
    {
        this.storeFormat = format;
        this.recordFormat = format.recordFormat();
        this.dbFileName = new File(baseFileName.getAbsolutePath() + ".db");
        this.idGenerator = idGenerator;
        this.pageCache = pageCache;
        this.fs = fs;
    }

    @Override
    public CURSOR cursor()
    {
        return storeFormat.createCursor(file, toolkit);
    }

    @Override
    public RECORD read( long id )
    {
        long pageId = toolkit.pageId( id );
        int offset = toolkit.recordOffset( id );

        try ( PageCursor cursor = file.io( pageId, PF_SHARED_LOCK | PF_NO_GROW ) )
        {
            if ( cursor.next() )
            {
                RECORD record;
                do
                {
                    record = recordFormat.deserialize( cursor, offset, id );
                } while ( cursor.retry() );

                return record;
            }
            else
            {
                throw new InvalidRecordException( recordFormat.recordName() + "[" + id + "] not in use" );
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    @Override
    public void write( RECORD record )
    {
        long id = recordFormat.id( record );
        long pageId = toolkit.pageId( id );
        int offset = toolkit.recordOffset( id );

        try ( PageCursor cursor = file.io( pageId, PF_EXCLUSIVE_LOCK ) )
        {
            if ( cursor.next() )
            {
                do
                {
                    recordFormat.serialize( cursor, offset, record );
                } while ( cursor.retry() );
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    @Override
    public long allocate()
    {
        return idGenerator.allocate();
    }

    @Override
    public void free( long id )
    {
        idGenerator.free(id);
    }

    @Override
    public void init() throws Throwable
    {
        if(!fs.fileExists( dbFileName ))
        {
            createNewStore();
        }
        else
        {
            initializeToolkit();
        }
    }

    private void createNewStore() throws IOException
    {
        fs.mkdirs( dbFileName.getParentFile() );
        fs.create( dbFileName );
        try(StoreChannel channel = fs.open( dbFileName, "rw" ))
        {
            storeFormat.createStore( channel );
        }

        initializeToolkit();

        // If this is the first time the store is started, and the store has a header, we need to reserve enough
        // initial records to make space for that header.
        int headerSize = storeFormat.headerSize();
        while(headerSize > 0)
        {
            // "Throw away" record slots at the beginning of the file until we've covered the full size of the header
            allocate();
            headerSize -= toolkit.recordSize();
        }
    }

    private void initializeToolkit() throws IOException
    {
        try(StoreChannel channel = fs.open( dbFileName, "rw" ))
        {
            int recordSize = storeFormat.recordSize( channel );
            int headerSize = storeFormat.headerSize();
            int firstRecordId = headerSize == 0 ? 0 : (int) Math.ceil( headerSize / (1.0 * recordSize) );

            // Note that the store page size is always divisible by recordSize. Since the pageCache reuses pages across
            // stores, it has a larger in-memory page size, but it will honor the size we calculate here as the on-disk
            // size for our file. In any case, the calculation below tries to fit as many records as possible within
            // the page size used in-memory by the pageCache.
            int pageSize = pageCache.pageSize() - pageCache.pageSize() % recordSize;

            toolkit = new StoreToolkit( recordSize, pageSize, firstRecordId );
        }
    }

    @Override
    public void start() throws Throwable
    {
        file = pageCache.map( dbFileName, toolkit.pageSize() );
    }

    @Override
    public void stop() throws Throwable
    {
        file.close();
        file = null;
    }
}
