package org.neo4j.backup.test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.neo4j.backup.BackupService;
import org.neo4j.backup.OnlineBackupKernelExtension;
import org.neo4j.backup.OnlineBackupSettings;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.index.Index;
import org.neo4j.kernel.DefaultFileSystemAbstraction;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.core.KernelPanicEventGenerator;
import org.neo4j.kernel.impl.nioneo.store.FileSystemAbstraction;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.storemigration.CurrentDatabase;
import org.neo4j.kernel.impl.transaction.XaDataSourceManager;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.logging.DevNullLoggingService;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.TargetDirectory;

import static junit.framework.Assert.assertNotNull;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import static org.neo4j.index.impl.lucene.LuceneDataSource.DEFAULT_NAME;
import static org.neo4j.kernel.impl.nioneo.xa.NeoStoreXaDataSource.DEFAULT_DATA_SOURCE_NAME;

public class BackupServiceIT
{
    private static final TargetDirectory target = TargetDirectory.forTest( BackupServiceIT.class );
    @Rule
    public TargetDirectory.TestDirectory testDirectory = target.testDirectory();

    public static final int BACKUP_PORT = 6362;
    public static final String BACKUP_HOST = "localhost";
    public static final String BACKUP_HOST_PORT = BACKUP_HOST + ":" + BACKUP_PORT;

    private FileSystemAbstraction fileSystem;
    private File storeDir;
    private File backupDir;

    @Before
    public void setup() throws IOException
    {
        fileSystem = new DefaultFileSystemAbstraction();

        storeDir = new File( testDirectory.directory(), "store_dir" );
        fileSystem.deleteRecursively( storeDir );
        fileSystem.mkdir( storeDir );

        backupDir = new File( testDirectory.directory(), "backup_dir" );
        fileSystem.deleteRecursively( backupDir );
    }

    @Test
    public void shouldThrowExceptionWhenDoingFullBackupOnADirectoryContainingANeoStore() throws Exception
    {
        // given
        fileSystem.mkdir( backupDir );
        fileSystem.create( new File( backupDir, NeoStore.DEFAULT_NAME ) ).close();

        try
        {
            // when
            new BackupService( fileSystem ).doFullBackup( "", 0, backupDir.getAbsolutePath(), true, new Config() );
        }
        catch ( RuntimeException ex )
        {
            // then
            assertThat( ex.getMessage(), containsString( "already contains a database" ) );
        }
    }

    @Test
    public void shouldCopyStoreFiles() throws Throwable
    {
        // given
        GraphDatabaseService db = createDb( storeDir, defaultBackupPortHostParams() );
        createAndIndexNode( db, 1 );

        // when
        BackupService backupService = new BackupService( fileSystem );
        backupService.doFullBackup( BACKUP_HOST, BACKUP_PORT, backupDir.getAbsolutePath(), false,
                new Config( defaultBackupPortHostParams() ) );
        db.shutdown();

        // then
        File[] files = fileSystem.listFiles( backupDir );

        for ( final String fileName : CurrentDatabase.fileNames() )
        {
            assertThat( files, hasFile( fileName ) );
        }

        assertEquals( DbRepresentation.of( storeDir ), DbRepresentation.of( backupDir ) );
    }

    @Test
    public void shouldFindTransactionLogContainingLastNeoStoreAndLuceneTransactionInAnEmptyStore() throws IOException
    {
        // given
        GraphDatabaseService db = createDb( storeDir, defaultBackupPortHostParams() );

        // when
        BackupService backupService = new BackupService( fileSystem );
        backupService.doFullBackup( BACKUP_HOST, BACKUP_PORT, backupDir.getAbsolutePath(), false,
                new Config( defaultBackupPortHostParams() ) );
        db.shutdown();

        // then
        assertEquals( DbRepresentation.of( storeDir ), DbRepresentation.of( backupDir ) );

        XaDataSourceManager xaDataSourceManager = xaDataSourceManager( backupDir );

        XaDataSource neoStoreXaDataSource = xaDataSourceManager.getXaDataSource( DEFAULT_DATA_SOURCE_NAME );
        long lastCommittedTxId = neoStoreXaDataSource.getLastCommittedTxId();
        assertNotNull( neoStoreXaDataSource.getMasterForCommittedTx( lastCommittedTxId ) );

        XaDataSource luceneDataSource = xaDataSourceManager.getXaDataSource( DEFAULT_NAME );
        long lastCommittedLuceneTxId = luceneDataSource.getLastCommittedTxId();
        assertNotNull( luceneDataSource.getMasterForCommittedTx( lastCommittedLuceneTxId ) );
    }

    @Test
    public void shouldFindTransactionLogContainingLastNeoStoreTransaction() throws Throwable
    {
        // given
        GraphDatabaseService db = createDb( storeDir, defaultBackupPortHostParams() );
        createAndIndexNode( db, 1 );

        // when
        BackupService backupService = new BackupService( fileSystem );
        backupService.doFullBackup( BACKUP_HOST, BACKUP_PORT, backupDir.getAbsolutePath(), false,
                new Config( defaultBackupPortHostParams() ) );
        db.shutdown();

        // then
        assertEquals( DbRepresentation.of( storeDir ), DbRepresentation.of( backupDir ) );

        XaDataSourceManager xaDataSourceManager = xaDataSourceManager( backupDir );
        XaDataSource neoStoreXaDataSource = xaDataSourceManager.getXaDataSource( DEFAULT_DATA_SOURCE_NAME );
        long lastCommittedTxId = neoStoreXaDataSource.getLastCommittedTxId();

        assertNotNull( neoStoreXaDataSource.getMasterForCommittedTx( lastCommittedTxId ) );
    }

    @Test
    public void shouldFindTransactionLogContainingLastLuceneTransaction() throws Throwable
    {
        // given
        GraphDatabaseService db = createDb( storeDir, defaultBackupPortHostParams() );
        createAndIndexNode( db, 1 );

        // when
        BackupService backupService = new BackupService( fileSystem );
        backupService.doFullBackup( BACKUP_HOST, BACKUP_PORT, backupDir.getAbsolutePath(), false,
                new Config( defaultBackupPortHostParams() ) );
        db.shutdown();

        // then
        assertEquals( DbRepresentation.of( storeDir ), DbRepresentation.of( backupDir ) );

        XaDataSourceManager xaDataSourceManager = xaDataSourceManager( backupDir );
        XaDataSource luceneDataSource = xaDataSourceManager.getXaDataSource( DEFAULT_NAME );
        long lastCommittedLuceneTxId = luceneDataSource.getLastCommittedTxId();

        assertNotNull( luceneDataSource.getMasterForCommittedTx( lastCommittedLuceneTxId ) );
    }

    @Test
    public void shouldContainTransactionsThatHappenDuringBackupProcess() throws Throwable
    {
        // given
        Map<String, String> params = defaultBackupPortHostParams();
        params.put( OnlineBackupSettings.online_backup_enabled.name(), "false" );

        final GraphDatabaseAPI db = createDb( storeDir, params );
        createAndIndexNode( db, 1 );

        final CountDownLatch countDownLatch = new CountDownLatch( 1 );

        XaDataSourceManager xaDataSourceManager = db.getDependencyResolver()
                .resolveDependency( XaDataSourceManager.class );

        KernelPanicEventGenerator kpeg = db.getDependencyResolver().resolveDependency(
                KernelPanicEventGenerator.class );

        OnlineBackupKernelExtension backup = new OnlineBackupKernelExtension(
                new Config( defaultBackupPortHostParams() ),
                db,
                xaDataSourceManager,
                kpeg,
                new DevNullLoggingService(),
                new Monitors(), countDownLatch );
        backup.start();

        // when
        BackupService backupService = new BackupService( fileSystem );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute( new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    countDownLatch.await();
                    for ( int i = 0; i < 10; i++ )
                    {
                        createAndIndexNode( db, i );
                    }

                }
                catch ( InterruptedException e )
                {
                    e.printStackTrace();
                }
            }
        } );

        backupService.doFullBackup( BACKUP_HOST, BACKUP_PORT, backupDir.getAbsolutePath(), false,
                new Config( params ) );
        db.shutdown();

        // then
        assertEquals( DbRepresentation.of( storeDir ), DbRepresentation.of( backupDir ) );
    }

    @Test
    public void shouldKeepAStiffUpperLipWhenTryingToRetrieveANodeThatWasAlreadyDeleted() throws Throwable
    {
        // given
        Map<String, String> params = defaultBackupPortHostParams();
        params.put( OnlineBackupSettings.online_backup_enabled.name(), "false" );

        final GraphDatabaseAPI db = createDb( storeDir, params );

        final int numberOfNodes = 10;

        for ( int i = 0; i < numberOfNodes; i++ )
        {
            createNode( db, i );
            try(Transaction tx = db.beginTx()) {
                db.schema().indexFor( DynamicLabel.label( "label" + i ) );
                tx.success();
            }
        }

        final CountDownLatch countDownLatch = new CountDownLatch( 1 );

        XaDataSourceManager xaDataSourceManager = db.getDependencyResolver()
                .resolveDependency( XaDataSourceManager.class );

        KernelPanicEventGenerator kpeg = db.getDependencyResolver().resolveDependency(
                KernelPanicEventGenerator.class );

        OnlineBackupKernelExtension backup = new OnlineBackupKernelExtension(
                new Config( defaultBackupPortHostParams() ),
                db,
                xaDataSourceManager,
                kpeg,
                new DevNullLoggingService(),
                new Monitors(), countDownLatch );
        backup.start();

        // when
        BackupService backupService = new BackupService( fileSystem );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute( new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    countDownLatch.await();
                    for ( int i = 0; i < numberOfNodes; i++ )
                    {
                        try ( Transaction tx = db.beginTx() )
                        {
                            Node node = db.getNodeById( i );
                            node.setProperty( "id", Integer.MAX_VALUE + i );
                            tx.success();
                        }

                        try ( Transaction tx = db.beginTx() )
                        {
                            Node node = db.getNodeById( i );
                            node.delete();
                            tx.success();
                        }
                    }

                }
                catch ( InterruptedException e )
                {
                    e.printStackTrace();
                }
            }
        } );

        backupService.doFullBackup( BACKUP_HOST, BACKUP_PORT, backupDir.getAbsolutePath(), false,
                new Config( params ) );
        db.shutdown();

        // then
        assertEquals( DbRepresentation.of( storeDir ), DbRepresentation.of( backupDir ) );
    }

    private Map<String, String> defaultBackupPortHostParams()
    {
        Map<String, String> params = new HashMap<String, String>();
        params.put( OnlineBackupSettings.online_backup_server.name(), BACKUP_HOST_PORT );
        return params;
    }

    private XaDataSourceManager xaDataSourceManager( File backupDir )
    {
        return ((EmbeddedGraphDatabase) new GraphDatabaseFactory().
                newEmbeddedDatabase( backupDir.getAbsolutePath() )).getDependencyResolver()
                .resolveDependency( XaDataSourceManager.class );
    }

    private GraphDatabaseAPI createDb( File storeDir, Map<String, String> params )
    {
        return (GraphDatabaseAPI) new GraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder( storeDir.getPath() )
                .setConfig( params )
                .newGraphDatabase();
    }

    private void createNode( GraphDatabaseService db, int i )
    {
        Transaction tx = db.beginTx();
        try
        {
            Node node = db.createNode();
            node.setProperty( "id", System.currentTimeMillis() + i );
            node.addLabel( DynamicLabel.label( "label" + i ) );
            tx.success();
        }
        finally
        {
            tx.finish();
        }
    }

    private void createAndIndexNode( GraphDatabaseService db, int i )
    {
        Transaction tx = db.beginTx();
        try
        {
            Index<Node> index = db.index().forNodes( "delete_me" );
            Node node = db.createNode();
            node.setProperty( "id", System.currentTimeMillis() + i );
            index.add( node, "delete", "me" );
            tx.success();
        }
        finally
        {
            tx.finish();
        }
    }

    private BaseMatcher<File[]> hasFile( final String fileName )
    {
        return new BaseMatcher<File[]>()
        {
            @Override
            public boolean matches( Object o )
            {
                File[] files = (File[]) o;
                if ( files == null )
                {
                    return false;
                }
                for ( File file : files )
                {
                    if ( file.getAbsolutePath().contains( fileName ) )
                    {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( String.format( "[%s] in list of copied files", fileName ) );
            }
        };
    }
}
