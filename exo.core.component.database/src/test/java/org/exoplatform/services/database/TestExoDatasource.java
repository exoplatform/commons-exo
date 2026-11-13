/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.services.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

import org.hsqldb.jdbc.JDBCDataSource;

import org.exoplatform.services.database.table.ExoLongID;
import org.exoplatform.services.database.table.IDGenerator;

/**
 * The table manager and the ID generator of a datasource, on an HSQLDB database.
 */
public class TestExoDatasource extends TestCase
{
   private ExoDatasource datasource;

   @Override
   protected void setUp() throws Exception
   {
      JDBCDataSource ds = new JDBCDataSource();
      ds.setURL("jdbc:hsqldb:mem:" + getName());
      ds.setUser("sa");
      ds.setPassword("");
      datasource = new ExoDatasource(ds);
   }

   @Override
   protected void tearDown() throws Exception
   {
      try (Connection connection = datasource.getDatasource().getConnection();
           Statement statement = connection.createStatement())
      {
         statement.execute("SHUTDOWN");
      }
   }

   public void testATableIsCreatedWithTheColumnsOfItsAnnotationAndDropped() throws Exception
   {
      DBTableManager tables = datasource.getDBTableManager();
      assertFalse(tables.hasTable(Mock.class));

      tables.createTable(Mock.class, true);
      assertTrue(tables.hasTable(Mock.class));
      Set<String> columns = new HashSet<>();
      try (Connection connection = datasource.getDatasource().getConnection();
           ResultSet rs = connection.getMetaData().getColumns(null, null, "MOCKTABLE", null))
      {
         while (rs.next())
         {
            columns.add(rs.getString("COLUMN_NAME").toUpperCase());
         }
      }
      assertTrue(columns.toString(), columns.containsAll(Set.of("NAME", "STATUS", "START", "PASS")));

      tables.dropTable(Mock.class);
      assertFalse(tables.hasTable(Mock.class));
   }

   public void testTheIdsOfATypeFollowEachOtherAcrossItsBlocks() throws Exception
   {
      IDGenerator generator = new IDGenerator(datasource);
      for (long expected = 1; expected <= 4 * ExoLongID.BLOCK_SIZE; expected++)
      {
         assertEquals(expected, generator.generateLongId(Mock.class));
      }
      // Each type has its own sequence
      assertEquals(1, generator.generateLongId(ExoLongID.class));
   }
}
