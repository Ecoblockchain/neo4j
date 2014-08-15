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
package org.neo4j.kernel.impl.store.standard;

import java.io.IOException;

import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.impl.nioneo.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.Store;

import static org.neo4j.io.pagecache.PagedFile.PF_READ_AHEAD;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_LOCK;
import static org.neo4j.io.pagecache.PagedFile.PF_TRANSIENT;
import static org.neo4j.kernel.impl.store.Store.SF_REVERSE_CURSOR;
import static org.neo4j.kernel.impl.store.Store.SF_SCAN;
import static org.neo4j.kernel.impl.store.standard.StoreFormat.RecordFormat;

/**
 * A complete cursor implementation to be used on it's own or as a base class for building custom cursors.
 * Record cursors benefit from format-specific methods that allow interacting with whatever record the cursor is currently
 * pointing to. The recommended mechanism here is to use this as a base, and lean on it's ability to correctly position
 * the cursor and handle interaction with the page cache cursor, and then simply access the {@link #pageCursor} in here,
 * along with the {@link #currentRecordOffset}. See the protected fields in this class for more useful components.
 */
public class BaseRecordCursor<RECORD, FORMAT extends RecordFormat<RECORD>> implements Store.RecordCursor<RECORD>
{
    private final PagedFile file;
    /** Used to control how the cursor moves: +1 for a regular cursor, -1 for a reversed cursor */
    private final byte stepSize;

    // We share most of our fields with subclasses, as this is meant as an easy-to-use base for building custom
    // record cursors.

    protected final StoreToolkit toolkit;
    protected final FORMAT format;
    private final int pageCacheFlags;

    protected PageCursor pageCursor;
    protected long currentRecordId = -1;
    protected int  currentRecordOffset = -1;

    public BaseRecordCursor( PagedFile file, StoreToolkit toolkit, FORMAT format, int flags )
    {
        this.file = file;
        this.toolkit = toolkit;
        this.format = format;
        this.pageCacheFlags = pageCursorFlags( flags );
        if((flags & SF_REVERSE_CURSOR) == 0)
        {
            this.currentRecordId = toolkit.firstRecordId() - 1;
            this.stepSize = 1;
        }
        else
        {
            this.currentRecordId = toolkit.highestKnownId() + 1;
            this.stepSize = -1;
        }
    }

    @Override
    public RECORD currentRecord()
    {
        return format.deserialize( pageCursor, currentRecordOffset, currentRecordId );
    }

    @Override
    public long currentId()
    {
        return currentRecordId;
    }

    @Override
    public boolean currentInUse()
    {
        return format.inUse( pageCursor, currentRecordOffset );
    }

    @Override
    public boolean next( long id )
    {
        try
        {
            if(id < 0)
            {
                return false;
            }

            long pageId = toolkit.pageId( id );
            currentRecordId = id;
            currentRecordOffset = toolkit.recordOffset( id );

            // This may be the first next() call, so we may need to initialize a PageCursor
            if ( pageCursor == null )
            {
                pageCursor = file.io( pageId, pageCacheFlags );
                return pageCursor.next( pageId );
            }
            // Either we're on the right page, or we move to it
            else return pageId == pageCursor.getCurrentPageId() || pageCursor.next( pageId );
        }
        catch(IOException e)
        {
            throw new UnderlyingStorageException( "Failed to read record " + id + ".", e );
        }
    }

    @Override
    public boolean next()
    {
        while(next(currentRecordId + stepSize))
        {
            if( currentInUse())
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close()
    {
        if ( pageCursor != null )
        {
            pageCursor.close();
        }
    }

    /** Given our input flags, figure out what kind of page cursor setup we'll need */
    private int pageCursorFlags( int storeCursorFlags )
    {
        int flags = PF_SHARED_LOCK;
        if((storeCursorFlags & SF_SCAN) != 0)
        {
            flags |= PF_TRANSIENT;
            if( (storeCursorFlags & SF_REVERSE_CURSOR) == 0 )
            {
                // Note: We only use read-ahead if we are scanning forward. For OLAP type use cases, however, scanning
                // in either direction is not uncommon, so we should expand the page cursor to support backwards scans.
                flags |= PF_READ_AHEAD;
            }
        }
        return flags;
    }
}
