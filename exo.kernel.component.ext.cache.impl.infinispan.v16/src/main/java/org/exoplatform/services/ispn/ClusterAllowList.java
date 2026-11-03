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

import java.io.ObjectInputFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.infinispan.commons.configuration.ClassAllowList;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The classes a node deserializes from the cluster, one list for every path an object arrives by: JGroups' own
 * deserialization, through the {@value #JGROUPS_FILTER_PROPERTY} system property set before any channel is created;
 * the RPC services, through {@link #getFilter()}; and the cache marshaller ({@link ClusterJavaSerializationMarshaller}),
 * through {@link #getInfinispanAllowList()}, which also checks what it writes.
 * <p>
 * In order: Infinispan's built-in denials, the classes Infinispan always accepts, the platform's packages, the JDK
 * types that cached values and exceptions carry with their serializable superclasses, the entries of the
 * {@value #ALLOW_LIST_PROPERTY} property, then everything else denied.
 */
public final class ClusterAllowList
{
   /**
    * The entries integrators add to the list, comma-separated: a class name, <code>pkg.*</code> (the package) or
    * <code>pkg.**</code> (with its subpackages).
    */
   public static final String ALLOW_LIST_PROPERTY = "exo.cluster.serialization.allowList";

   /**
    * The system property JGroups reads its deserialization filter from, once.
    */
   public static final String JGROUPS_FILTER_PROPERTY = "jgroups.deserialization.filter";

   /**
    * The system properties that would widen Infinispan's allow-list only, refused.
    */
   static final List<String> INFINISPAN_ALLOW_LIST_PROPERTIES =
      List.of("infinispan.deserialization.allowlist.classes", "infinispan.deserialization.allowlist.regexps");

   /**
    * Infinispan's built-in denials (<code>ClassAllowList</code>), denied first on the paths Infinispan does not cover:
    * a prefix ending with a dot denies a package and its subpackages, any other the classes it starts.
    */
   static final List<String> DENIED_PREFIXES = List.of("bsh.",
                                                       "clojure.",
                                                       "com.mchange.",
                                                       "com.sun.rowset.",
                                                       "groovy.",
                                                       "java.rmi.",
                                                       "javax.management.remote.",
                                                       "javax.naming.InitialContext",
                                                       "ognl.",
                                                       "org.apache.commons.collections.functors.",
                                                       "org.apache.commons.collections4.functors.",
                                                       "org.apache.xalan.",
                                                       "org.codehaus.groovy.",
                                                       "org.hibernate.property.access.",
                                                       "org.mozilla.javascript.",
                                                       "org.springframework.core.",
                                                       "org.springframework.aop.");

   /**
    * The classes Infinispan always accepts (<code>ClassAllowList</code>), primitive arrays aside, which the JDK filter
    * accepts.
    */
   static final List<String> ALWAYS_ALLOWED = List.of("java.lang.Boolean",
                                                      "java.lang.Byte",
                                                      "java.lang.Character",
                                                      "java.lang.Short",
                                                      "java.lang.Integer",
                                                      "java.lang.Long",
                                                      "java.lang.Float",
                                                      "java.lang.Double",
                                                      "java.lang.Number",
                                                      "java.lang.String",
                                                      "java.lang.Enum",
                                                      "java.math.BigInteger",
                                                      "java.math.BigDecimal",
                                                      "java.util.Date",
                                                      "java.time.Ser",
                                                      "java.time.Instant",
                                                      "org.infinispan.commons.util.KeyValueWithPrevious");

   static final List<String> PLATFORM_PACKAGES = List.of("org.exoplatform.**", "io.meeds.**", "org.gatein.**");

   /**
    * The JDK types that cached values and exceptions carry, with their serializable superclasses, since the filter
    * checks the descriptor of each class a stream holds.
    */
   static final List<String> JDK_TYPES = List.of(
                                                 // Exceptions and errors, and what they hold
                                                 "java.lang.Throwable",
                                                 "java.lang.Exception",
                                                 "java.lang.RuntimeException",
                                                 "java.lang.Error",
                                                 "java.lang.StackTraceElement",
                                                 "java.lang.ArithmeticException",
                                                 "java.lang.ArrayIndexOutOfBoundsException",
                                                 "java.lang.ArrayStoreException",
                                                 "java.lang.ClassCastException",
                                                 "java.lang.ClassNotFoundException",
                                                 "java.lang.CloneNotSupportedException",
                                                 "java.lang.IllegalArgumentException",
                                                 "java.lang.IllegalMonitorStateException",
                                                 "java.lang.IllegalStateException",
                                                 "java.lang.IndexOutOfBoundsException",
                                                 "java.lang.InterruptedException",
                                                 "java.lang.NegativeArraySizeException",
                                                 "java.lang.NullPointerException",
                                                 "java.lang.NumberFormatException",
                                                 "java.lang.ReflectiveOperationException",
                                                 "java.lang.SecurityException",
                                                 "java.lang.StringIndexOutOfBoundsException",
                                                 "java.lang.UnsupportedOperationException",
                                                 "java.lang.AssertionError",
                                                 "java.lang.ExceptionInInitializerError",
                                                 "java.lang.LinkageError",
                                                 "java.lang.NoClassDefFoundError",
                                                 "java.lang.OutOfMemoryError",
                                                 "java.lang.StackOverflowError",
                                                 "java.lang.VirtualMachineError",
                                                 "java.io.IOException",
                                                 "java.io.EOFException",
                                                 "java.io.FileNotFoundException",
                                                 "java.io.UncheckedIOException",
                                                 "java.util.ConcurrentModificationException",
                                                 "java.util.MissingResourceException",
                                                 "java.util.NoSuchElementException",
                                                 "java.util.concurrent.CancellationException",
                                                 "java.util.concurrent.CompletionException",
                                                 "java.util.concurrent.ExecutionException",
                                                 "java.util.concurrent.RejectedExecutionException",
                                                 "java.util.concurrent.TimeoutException",
                                                 // Collections, and the element types of the arrays they write
                                                 "java.lang.Object",
                                                 "java.util.Map$Entry",
                                                 "java.util.ArrayList",
                                                 "java.util.LinkedList",
                                                 "java.util.HashMap",
                                                 "java.util.LinkedHashMap",
                                                 "java.util.TreeMap",
                                                 "java.util.HashSet",
                                                 "java.util.LinkedHashSet",
                                                 "java.util.TreeSet",
                                                 "java.util.Arrays$ArrayList",
                                                 // List.of, Set.of and Map.of: their serialized form, then the class it
                                                 // resolves to, which the filter checks as well
                                                 "java.util.CollSer",
                                                 "java.util.ImmutableCollections$*",
                                                 "java.util.AbstractMap$SimpleEntry",
                                                 "java.util.AbstractMap$SimpleImmutableEntry",
                                                 "java.util.Collections$*",
                                                 "java.util.concurrent.ConcurrentHashMap",
                                                 "java.util.concurrent.ConcurrentHashMap$Segment",
                                                 "java.util.concurrent.locks.ReentrantLock",
                                                 "java.util.concurrent.locks.ReentrantLock$Sync",
                                                 "java.util.concurrent.locks.ReentrantLock$NonfairSync",
                                                 "java.util.concurrent.locks.AbstractQueuedSynchronizer",
                                                 "java.util.concurrent.locks.AbstractOwnableSynchronizer",
                                                 "java.util.concurrent.CopyOnWriteArrayList",
                                                 "java.util.concurrent.CopyOnWriteArraySet",
                                                 // Values
                                                 "java.util.Locale",
                                                 "java.util.UUID");

   private static final Pattern ENTRY =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*(\\.\\*\\*?)?");

   private static final Log LOG = ExoLogger.getLogger("exo.kernel.component.ext.cache.impl.infinispan.v16.ClusterAllowList");

   private static ClusterAllowList instance;

   private final List<String> entries;

   private final String filter;

   /**
    * @param property the value of the {@value #ALLOW_LIST_PROPERTY} property, <code>null</code> if not set
    * @throws IllegalStateException if an entry of the property has another form than the ones it accepts
    */
   ClusterAllowList(String property)
   {
      Set<String> allowed = new LinkedHashSet<>(ALWAYS_ALLOWED);
      allowed.addAll(PLATFORM_PACKAGES);
      allowed.addAll(JDK_TYPES);
      allowed.addAll(parse(property));
      this.entries = List.copyOf(allowed);
      List<String> patterns = new ArrayList<>();
      for (String prefix : DENIED_PREFIXES)
      {
         patterns.add("!" + prefix + (prefix.endsWith(".") ? "**" : "*"));
      }
      patterns.addAll(entries);
      patterns.add("!*");
      this.filter = String.join(";", patterns);
   }

   /**
    * Builds the list of this node from the {@value #ALLOW_LIST_PROPERTY} property on first call, then sets the
    * {@value #JGROUPS_FILTER_PROPERTY} system property and logs the digest of the list, which must be the same on every
    * node.
    *
    * @return the list of this node
    * @throws IllegalStateException if the property is malformed, or if a system property would widen Infinispan's
    *           allow-list only
    */
   public static synchronized ClusterAllowList getInstance()
   {
      if (instance == null)
      {
         checkSystemProperties();
         ClusterAllowList allowList = new ClusterAllowList(PropertyManager.getProperty(ALLOW_LIST_PROPERTY));
         String previous = System.setProperty(JGROUPS_FILTER_PROPERTY, allowList.filter);
         if (previous != null && !previous.equals(allowList.filter))
         {
            LOG.warn("The system property '{}' is replaced by the cluster serialization allow-list: add entries to the"
               + " '{}' property instead", JGROUPS_FILTER_PROPERTY, ALLOW_LIST_PROPERTY);
         }
         LOG.info("Cluster serialization allow-list: {} entries, digest {}", allowList.entries.size(),
            allowList.getDigest());
         instance = allowList;
      }
      return instance;
   }

   static void checkSystemProperties()
   {
      for (String name : INFINISPAN_ALLOW_LIST_PROPERTIES)
      {
         if (System.getProperty(name) != null)
         {
            throw new IllegalStateException("The system property '" + name
               + "' would accept classes on the cache path only: list them in the '" + ALLOW_LIST_PROPERTY
               + "' property instead");
         }
      }
   }

   /**
    * @return the list in the syntax of the JDK serialization filters
    */
   public String getFilterPattern()
   {
      return filter;
   }

   /**
    * @return a filter applying the list
    */
   public ObjectInputFilter getFilter()
   {
      return ObjectInputFilter.Config.createFilter(filter);
   }

   /**
    * @return the list as Infinispan's class names and regular expressions, to which Infinispan adds its own denials and
    *         the classes it always accepts
    */
   public ClassAllowList getInfinispanAllowList()
   {
      Set<String> classes = new LinkedHashSet<>();
      List<String> regexps = new ArrayList<>();
      for (String entry : entries)
      {
         if (entry.endsWith(".**"))
         {
            regexps.add("^" + Pattern.quote(entry.substring(0, entry.length() - 2)) + ".+$");
         }
         else if (entry.endsWith(".*"))
         {
            regexps.add("^" + Pattern.quote(entry.substring(0, entry.length() - 1)) + "[^.]+$");
         }
         else if (entry.endsWith("$*"))
         {
            regexps.add("^" + Pattern.quote(entry.substring(0, entry.length() - 1)) + ".+$");
         }
         else
         {
            classes.add(entry);
         }
      }
      return new ClassAllowList(classes, regexps, ClusterAllowList.class.getClassLoader());
   }

   /**
    * @return the first 16 hexadecimal digits of the SHA-256 digest of the list, which Ops compare across nodes
    */
   public String getDigest()
   {
      try
      {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(filter.getBytes(StandardCharsets.UTF_8));
         return HexFormat.of().formatHex(digest, 0, 8);
      }
      catch (NoSuchAlgorithmException e)
      {
         throw new IllegalStateException("SHA-256 is not available", e);
      }
   }

   private static List<String> parse(String property)
   {
      List<String> entries = new ArrayList<>();
      if (property == null)
      {
         return entries;
      }
      for (String entry : property.split(","))
      {
         String trimmed = entry.trim();
         if (trimmed.isEmpty())
         {
            continue;
         }
         if (!ENTRY.matcher(trimmed).matches())
         {
            throw new IllegalStateException("The entry '" + trimmed + "' of the property '" + ALLOW_LIST_PROPERTY
               + "' is neither a class name, nor 'pkg.*' (a package), nor 'pkg.**' (a package and its subpackages)");
         }
         entries.add(trimmed);
      }
      return entries;
   }
}
