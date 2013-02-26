package org.neo4j.kernel.impl.api.index;

public class Flipper
{
    private final FlippableIndexContext delegatingContext;
    private final IndexContext onlineContext;

    public Flipper( FlippableIndexContext delegatingContext, IndexContext onlineContext )
    {
        this.delegatingContext = delegatingContext;
        this.onlineContext = onlineContext;
    }
    
    public void flip( Runnable action )
    {
        IndexContext blockingContext = new BlockingIndexContext( onlineContext );
        delegatingContext.setDelegate( blockingContext );
        try
        {
            action.run();
        }
        finally
        {
            blockingContext.ready();
        }
        delegatingContext.setDelegate( onlineContext );
    }
}
