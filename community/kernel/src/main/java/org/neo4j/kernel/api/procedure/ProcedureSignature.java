/*
 * Copyright (c) 2002-2015 "Neo Technology,"
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
package org.neo4j.kernel.api.procedure;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.neo4j.helpers.Pair;

import static org.neo4j.helpers.Pair.pair;
import static org.neo4j.kernel.impl.store.Neo4jTypes.AnyType;

public class ProcedureSignature
{
    private final String[] namespace;
    private final String name;
    private final List<Pair<String,AnyType>> inputSignature;
    private final List<Pair<String,AnyType>> outputSignature;

    public ProcedureSignature( String[] namespace, String name,
            List<Pair<String,AnyType>> inputSignature,
            List<Pair<String,AnyType>> outputSignature )
    {
        this.namespace = namespace;
        this.name = name;
        this.inputSignature = inputSignature;
        this.outputSignature = outputSignature;
    }

    public String[] getNamespace()
    {
        return namespace;
    }

    public String getName()
    {
        return name;
    }

    public List<Pair<String,AnyType>> getInputSignature()
    {
        return inputSignature;
    }

    public List<Pair<String,AnyType>> getOutputSignature()
    {
        return outputSignature;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        { return true; }
        if ( o == null || getClass() != o.getClass() )
        { return false; }

        ProcedureSignature that = (ProcedureSignature) o;

        if ( !inputSignature.equals( that.inputSignature ) )
        { return false; }
        if ( !name.equals( that.name ) )
        { return false; }
        if ( !Arrays.equals( namespace, that.namespace ) )
        { return false; }
        if ( !outputSignature.equals( that.outputSignature ) )
        { return false; }

        return true;
    }

    @Override
    public int hashCode()
    {
        return name.hashCode();
    }

    public static class Builder
    {
        private final String[] namespace;
        private final String name;
        private final List<Pair<String,AnyType>> inputSignature = new LinkedList<>();
        private final List<Pair<String,AnyType>> outputSignature = new LinkedList<>();

        public Builder( String[] namespace, String name )
        {
            this.namespace = namespace;
            this.name = name;
        }

        /** Define an input field */
        public Builder in( String name, AnyType type )
        {
            inputSignature.add( pair( name, type ) );
            return this;
        }

        /** Define an output field */
        public Builder out( String name, AnyType type )
        {
            outputSignature.add( pair( name, type ) );
            return this;
        }

        public ProcedureSignature build()
        {
            return new ProcedureSignature(namespace, name, inputSignature, outputSignature );
        }
    }

    public static Builder procedureSignature(String[] namespace, String name)
    {
        return new Builder(namespace, name);
    }
}