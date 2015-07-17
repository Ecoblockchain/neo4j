package org.neo4j.kernel.impl.api.integrationtest;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.TopLevelTransaction;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.SchemaWriteOperations;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.procedure.ProcedureSignature;
import org.neo4j.kernel.api.procedure.RecordCursor;
import org.neo4j.kernel.impl.store.Neo4jTypes;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.neo4j.helpers.collection.IteratorUtil.asCollection;
import static org.neo4j.kernel.api.procedure.ProcedureSignature.procedureSignature;
import static org.neo4j.kernel.impl.store.Neo4jTypes.NTInteger;
import static org.neo4j.kernel.impl.store.Neo4jTypes.NTNode;
import static org.neo4j.kernel.impl.store.Neo4jTypes.NTText;
import static org.neo4j.kernel.impl.util.IoPrimitiveUtils.readAsString;

public class ProceduresKernelIT extends KernelIntegrationTest
{
    public static final Label PRODUCT = DynamicLabel.label( "PRODUCT" );

    @Test
    public void shouldCreateProcedure() throws Throwable
    {
        // Given
        SchemaWriteOperations ops = schemaWriteOperationsInNewTransaction();
        ProcedureSignature signature = procedureSignature( new String[]{"neo4j"}, "exampleProc" )
                .in( "name", NTText )
                .out( "age", NTInteger ).build();

        // When
        ops.procedureCreate( signature, "javascript", "yield record(1);" );

        // Then
        assertThat( asCollection( ops.proceduresGetAll() ),
                Matchers.<Collection<ProcedureSignature>>equalTo( asList( signature ) ) );

        // And when
        commit();

        // Then
        assertThat( asCollection( readOperationsInNewTransaction().proceduresGetAll() ),
                Matchers.<Collection<ProcedureSignature>>equalTo( asList( signature ) ) );
    }

    @Test
    public void shouldBeAbleToInvokeSimpleProcedure() throws Throwable
    {
        // Given
        ProcedureSignature signature = procedureSignature( new String[]{"neo4j"}, "exampleProc" )
                .in( "name", NTText )
                .out( "name", NTText ).build();

        // Create a procedure
        {
            SchemaWriteOperations ops = schemaWriteOperationsInNewTransaction();

            ops.procedureCreate( signature, "javascript", "yield record(name);\n" );
            commit();
        }

        ReadOperations ops = readOperationsInNewTransaction();

        // When
        RecordCursor res = ops.procedureCall( signature, new Object[]{"hello"} );

        // Then
        assertTrue( res.next() );
        assertTrue( Arrays.equals( new Object[]{"hello"}, res.getRecord() ) );
        assertFalse( res.next() );

        res.close();
    }

    @Test
    public void shouldBeAbleToWork() throws KernelException
    {
/*
        REPLACE PROCEDURE target.productPromotions(catEntryId: Integer):
        (promotionId: Text, parentId: Text, relType: Text) USING js "{
        var product = neo4j.db.findNodesByLabelAndProperty('PRODUCT', 'CATENTRY_ID', catEntryId).pop();

        if(product == null){
            log.warn("CATENTRY_ID value " + catEntryId + " not found");
            error("CATENTRY_ID value not found.")
        }

        var chain = target.getChain(product.getId()).column("p");

        for(var link in chain){
        for(var promotedRel in link.getRelationships(neo4j.OUTGOING,
                'APPLYPROMO', 'EXCLUDEPROMO')){
            var promotion = promotedRel.getEndNode();
            yield record( promotion.getProperty("PROMOTION_ID"),
                    promotion.getProperty("PARENT"),
                    promotedRel.getType().name());
        }
    }
    }"
*/

        // Given
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().indexFor( PRODUCT ).on( "ID" ).create();
            tx.success();
        }

        try ( Transaction tx = db.beginTx() )
        {
            db.schema().awaitIndexesOnline( 10, TimeUnit.SECONDS );

            Node product = db.createNode( PRODUCT );
            product.setProperty( "ID", "product1" );

            Node root = db.createNode();

            product.createRelationshipTo( root, DynamicRelationshipType.withName( "PARENT" ) );

            Node promo = db.createNode();
            promo.setProperty( "PROMOTION_ID", "promo1" );
            promo.setProperty( "PARENT", "promo2" );

            product.createRelationshipTo( promo, DynamicRelationshipType.withName( "APPLY" ) );

            Node promo2 = db.createNode();
            promo2.setProperty( "PROMOTION_ID", "promo2" );
            promo2.setProperty( "PARENT", "promo3" );

            root.createRelationshipTo( promo2, DynamicRelationshipType.withName( "EXCLUDE" ) );

            tx.success();
        }

        // Create procedures
        ProcedureSignature exampleProc = procedureSignature( new String[]{"neo4j"}, "exampleProc" )
                .in( "id", NTText )
                .out( "id", NTText ).out( "parent", NTText ).out( "type", NTText ).build();
        {
            SchemaWriteOperations ops = schemaWriteOperationsInNewTransaction();

            ops.procedureCreate( exampleProc, "javascript", readAsString( getClass().getResourceAsStream( "procedure1" +
                                                                                                          ".js" ) ));

            ProcedureSignature chain = procedureSignature( new String[]{"procs"}, "getChain" )
                    .in( "id", NTInteger )
                    .out( "link", NTNode).build();
            ops.procedureCreate( chain, "cypher", "MATCH (n)-[:PARENT*0..]->(link) WHERE id(n) = {id} RETURN " +
                                                            "link"
            );

            commit();
        }

        try ( Transaction tx = db.beginTx() )
        {
            ReadOperations ops = ((TopLevelTransaction) tx).getTransaction().acquireStatement().readOperations();

            // When
            RecordCursor res = ops.procedureCall( exampleProc, new Object[]{"product1"} );

            // Then
            while (res.next())
            {
                Object[] record = res.getRecord();
                for ( int i = 0; i < exampleProc.getOutputSignature().size(); i++ )
                {
                    Pair<String,Neo4jTypes.AnyType> arg =
                            exampleProc.getOutputSignature().get( i );
                    if (i > 0)
                        System.out.print(", ");
                    System.out.print(arg.first()+"="+record[i]);
                }
                System.out.println();
            }
            res.close();
/*
            assertTrue( res.next() );
            assertTrue( Arrays.equals( new Object[]{"1", "3", "APPLY"}, res.getRecord() ) );
            assertFalse( res.next() );
*/

            res.close();
        }
    }

    private InputStream streamOf( String s )
    {
        return new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) );
    }
}
