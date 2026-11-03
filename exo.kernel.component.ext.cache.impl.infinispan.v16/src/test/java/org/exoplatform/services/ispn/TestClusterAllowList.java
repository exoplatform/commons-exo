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
package org.exoplatform.services.ispn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.junit.Test;

import org.exoplatform.services.cache.ExoCacheInitException;

/**
 * Pins the cluster serialization allow-list: each kind of value the clustered caches and the RPC replies carry makes
 * the round trip, and anything else is refused on write and on read.
 */
public class TestClusterAllowList
{
   private final ClusterAllowList allowList = new ClusterAllowList(null);

   private final ClusterJavaSerializationMarshaller marshaller =
      new ClusterJavaSerializationMarshaller(allowList.getInfinispanAllowList());

   @Test
   public void testValuesMakeTheRoundTrip() throws Exception
   {
      Map<String, Object> map = new HashMap<>();
      map.put("key", List.of(1, 2L));
      List<Object> values = List.of("string",
                                    1,
                                    2L,
                                    3.0d,
                                    true,
                                    'c',
                                    new BigDecimal("1.5"),
                                    new byte[] {1, 2},
                                    new int[] {3},
                                    new String[] {"a", "b"},
                                    new Date(1000),
                                    // The creation date of a web notification
                                    new GregorianCalendar(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE),
                                    Instant.ofEpochSecond(1000),
                                    LocalDate.of(2026, 11, 3),
                                    LocalDateTime.of(2026, 11, 3, 12, 0),
                                    ZonedDateTime.of(2026, 11, 3, 12, 0, 0, 0, ZoneId.of("Europe/Paris")),
                                    Duration.ofSeconds(30),
                                    ZoneOffset.ofHours(1),
                                    URI.create("https://example.org/explorer"),
                                    Locale.FRANCE,
                                    UUID.randomUUID(),
                                    new ArrayList<>(List.of("a")),
                                    new LinkedList<>(List.of("a")),
                                    new HashSet<>(Set.of("a")),
                                    new LinkedHashSet<>(Set.of("a")),
                                    new TreeSet<>(Set.of("a")),
                                    map,
                                    new LinkedHashMap<>(map),
                                    new TreeMap<>(map),
                                    new ConcurrentHashMap<>(map),
                                    new CopyOnWriteArrayList<>(List.of("a")),
                                    List.of("a", "b"),
                                    Set.of("a"),
                                    Map.of("a", "b"),
                                    java.util.Arrays.asList("a", "b"),
                                    Collections.emptyList(),
                                    Collections.singletonMap("a", "b"),
                                    Collections.unmodifiableList(new ArrayList<>(List.of("a"))),
                                    Collections.synchronizedMap(new HashMap<>(map)),
                                    // The parameter of the filter of web notifications, a key of their list cache
                                    ImmutablePair.of("name", "value"),
                                    new PlatformValue("name", PlatformState.ACTIVE, List.of("a")));
      for (Object value : values)
      {
         Object copy = roundTrip(value);
         if (value instanceof byte[] bytes)
         {
            assertArrayEquals(bytes, (byte[])copy);
         }
         else if (value instanceof int[] ints)
         {
            assertArrayEquals(ints, (int[])copy);
         }
         else if (value instanceof Object[] array)
         {
            assertArrayEquals(array, (Object[])copy);
         }
         else if (value instanceof Collection || value instanceof Map)
         {
            assertEquals(value.toString(), copy.toString());
         }
         else
         {
            assertEquals(value, copy);
         }
      }
   }

   @Test
   public void testErrorRepliesMakeTheRoundTrip() throws Exception
   {
      Exception platformCause = new PlatformException("platform cause", null);
      Exception jdkCause = new IllegalStateException("JDK cause", new NullPointerException("null"));
      jdkCause.addSuppressed(new java.io.IOException("suppressed"));
      for (Exception cause : List.of(platformCause, jdkCause))
      {
         Exception error = new PlatformException("error", cause);
         Exception copy = (Exception)roundTrip(error);
         assertEquals(error.getMessage(), copy.getMessage());
         assertEquals(cause.getClass(), copy.getCause().getClass());
         assertEquals(cause.getMessage(), copy.getCause().getMessage());
         assertEquals(error.getStackTrace().length, copy.getStackTrace().length);
      }
      Throwable[] suppressed = ((Exception)roundTrip(jdkCause)).getSuppressed();
      assertEquals(1, suppressed.length);
   }

   @Test
   public void testClassOffTheListIsRefusedOnWrite()
   {
      Exception e = assertThrows(InvalidClassException.class, () -> marshaller.objectToByteBuffer(new Vector<>(List.of("a"))));
      assertTrue(e.getMessage(), e.getMessage().contains("java.util.Vector"));
      assertTrue(e.getMessage(), e.getMessage().contains(ClusterAllowList.ALLOW_LIST_PROPERTY));
      // A value holding one is refused as well
      assertThrows(InvalidClassException.class,
                   () -> marshaller.objectToByteBuffer(new ArrayList<>(List.of(new Vector<>(List.of("a"))))));
   }

   @Test
   public void testClassOffTheListIsRefusedOnRead() throws Exception
   {
      byte[] bytes = serialize(new Vector<>(List.of("a")));
      Exception e = assertThrows(Exception.class, () -> marshaller.objectFromByteBuffer(bytes));
      assertTrue(e.getMessage(), e.getMessage().contains("java.util.Vector"));

      ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes));
      input.setObjectInputFilter(allowList.getFilter());
      assertThrows(InvalidClassException.class, input::readObject);
   }

   @Test
   public void testPropertyEntriesWidenEveryPath() throws Exception
   {
      ClusterAllowList widened = new ClusterAllowList(" java.util.Vector , java.text.*,javax.swing.**");
      assertTrue(widened.getFilterPattern().contains(";java.util.Vector;java.text.*;javax.swing.**;!*"));
      ClassAllowList infinispan = widened.getInfinispanAllowList();
      assertTrue(infinispan.isSafeClass("java.util.Vector"));
      assertTrue(infinispan.isSafeClass("java.text.SimpleDateFormat"));
      assertFalse(infinispan.isSafeClass("java.text.spi.DateFormatProvider"));
      assertTrue(infinispan.isSafeClass("javax.swing.text.Document"));
      assertFalse(infinispan.isSafeClass("javax.swingx.Component"));

      Vector<String> vector = new Vector<>(List.of("a"));
      ClusterJavaSerializationMarshaller widenedMarshaller = new ClusterJavaSerializationMarshaller(infinispan);
      assertEquals(vector, widenedMarshaller.objectFromByteBuffer(widenedMarshaller.objectToByteBuffer(vector)));
      ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialize(vector)));
      input.setObjectInputFilter(widened.getFilter());
      assertEquals(vector, input.readObject());
   }

   @Test
   public void testMalformedEntriesStopTheStart()
   {
      for (String entry : List.of("!java.util.Vector", "java.util.Vector;java.util.Stack", "java.util.Vec*", "java.base/java.util.Vector",
                                  "maxdepth=10", "java.util.*.Vector", "java..Vector"))
      {
         Exception e = assertThrows(IllegalStateException.class, () -> new ClusterAllowList("java.util.Stack," + entry));
         assertTrue(e.getMessage(), e.getMessage().contains("'" + entry.trim() + "'"));
      }
   }

   @Test
   public void testDenialsComeFirst()
   {
      ClusterAllowList widened = new ClusterAllowList("org.springframework.**");
      String pattern = widened.getFilterPattern();
      assertTrue(pattern.startsWith("!bsh.**;"));
      assertTrue(pattern.indexOf("!org.springframework.core.**") < pattern.indexOf(";org.springframework.**"));
      assertTrue(pattern.contains("!javax.naming.InitialContext*;"));
      assertTrue(pattern.endsWith(";!*"));
   }

   @Test
   @SuppressWarnings("unchecked")
   public void testBuiltInsAreInfinispans() throws Exception
   {
      assertEquals(new HashSet<>((List<String>)infinispanField("DENIED_PREFIXES")),
                   new HashSet<>(ClusterAllowList.DENIED_PREFIXES));
      Set<String> alwaysAllowed = new HashSet<>((Set<String>)infinispanField("SYS_ALLOWED_CLASSES"));
      alwaysAllowed.removeIf(name -> name.startsWith("["));
      assertEquals(alwaysAllowed, new HashSet<>(ClusterAllowList.ALWAYS_ALLOWED));
   }

   @Test
   public void testInfinispanAllowListPropertiesStopTheStart()
   {
      for (String name : ClusterAllowList.INFINISPAN_ALLOW_LIST_PROPERTIES)
      {
         System.setProperty(name, "java.util.Vector");
         try
         {
            Exception e = assertThrows(IllegalStateException.class, ClusterAllowList::checkSystemProperties);
            assertTrue(e.getMessage(), e.getMessage().contains(name));
         }
         finally
         {
            System.clearProperty(name);
         }
      }
      ClusterAllowList.checkSystemProperties();
   }

   @Test
   public void testJGroupsFilterIsTheList()
   {
      assertEquals(ClusterAllowList.getInstance().getFilterPattern(),
                   System.getProperty(ClusterAllowList.JGROUPS_FILTER_PROPERTY));
      assertEquals(16, ClusterAllowList.getInstance().getDigest().length());
   }

   @Test
   public void testAllowListOfAnInfinispanConfigurationStopsTheStart() throws Exception
   {
      GlobalConfigurationBuilder builder = new GlobalConfigurationBuilder();
      Utils.configureSerialization(builder, "no-allow-list.xml");
      assertTrue(builder.serialization().getMarshaller() instanceof ClusterJavaSerializationMarshaller);

      String xml = "<infinispan xmlns=\"urn:infinispan:config:16.0\"><cache-container><serialization><allow-list>"
         + "<class>java.util.Vector</class></allow-list></serialization></cache-container></infinispan>";
      ConfigurationBuilderHolder holder =
         new ParserRegistry().parse(xml, org.infinispan.commons.dataconversion.MediaType.APPLICATION_XML);
      Exception e = assertThrows(ExoCacheInitException.class,
                                 () -> Utils.configureSerialization(holder.getGlobalConfigurationBuilder(), "allow-list.xml"));
      assertTrue(e.getMessage(), e.getMessage().contains("'allow-list.xml'"));
      assertTrue(e.getMessage(), e.getMessage().contains(ClusterAllowList.ALLOW_LIST_PROPERTY));
   }

   private Object roundTrip(Object value) throws Exception
   {
      Object copy = marshaller.objectFromByteBuffer(marshaller.objectToByteBuffer(value));
      ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialize(value)));
      input.setObjectInputFilter(allowList.getFilter());
      Object filtered = input.readObject();
      assertEquals(copy.getClass(), filtered.getClass());
      return copy;
   }

   private static byte[] serialize(Object value) throws IOException
   {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream output = new ObjectOutputStream(bytes))
      {
         output.writeObject(value);
      }
      return bytes.toByteArray();
   }

   private static Object infinispanField(String name) throws Exception
   {
      Field field = ClassAllowList.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(null);
   }

   enum PlatformState {
      ACTIVE
   }

   static final class PlatformValue implements Serializable
   {
      private static final long serialVersionUID = 1L;

      private final String name;

      private final PlatformState state;

      private final List<String> values;

      PlatformValue(String name, PlatformState state, List<String> values)
      {
         this.name = name;
         this.state = state;
         this.values = values;
      }

      @Override
      public boolean equals(Object o)
      {
         return o instanceof PlatformValue other && name.equals(other.name) && state == other.state
            && values.equals(other.values);
      }

      @Override
      public int hashCode()
      {
         return Objects.hash(name, state, values);
      }
   }

   static final class PlatformException extends Exception
   {
      private static final long serialVersionUID = 1L;

      PlatformException(String message, Throwable cause)
      {
         super(message, cause);
      }
   }
}
