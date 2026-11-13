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

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.infinispan.commons.CacheException;
import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.io.ByteBufferImpl;
import org.infinispan.commons.io.LazyByteArrayOutputStream;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The marshaller of the cached keys and values: Java serialization, which accepts on read only the classes of the
 * {@link ClusterAllowList}. It refuses as well to write a class off the list, so that a value the other nodes would
 * refuse fails on the node that writes it, before anything is sent. Each class refused is logged once, as a warning.
 * A write larger than the size {@link #WARN_SIZE_PROPERTY} sets proceeds, and is logged once for what it writes, as a
 * warning: a diagnosis of the values that load the cluster, not a limit.
 */
public class ClusterJavaSerializationMarshaller extends JavaSerializationMarshaller
{
   private static final Log         LOG      = ExoLogger.getLogger(ClusterJavaSerializationMarshaller.class);

   /**
    * The size in bytes above which a write is logged; 0 or less turns the warning off
    */
   public static final String       WARN_SIZE_PROPERTY = "exo.cluster.serialization.warnSize";

   static final int                 DEFAULT_WARN_SIZE  = 1024 * 1024;

   private static final Set<String> REFUSALS = ConcurrentHashMap.newKeySet();

   private static final Set<String> OVERSIZES = ConcurrentHashMap.newKeySet();

   private final ClassAllowList     allowList;

   private final int                warnSize;

   /**
    * A marshaller applying the list of this node.
    */
   public ClusterJavaSerializationMarshaller()
   {
      this(ClusterAllowList.getInstance().getInfinispanAllowList(),
           warnSize(PropertyManager.getProperty(WARN_SIZE_PROPERTY)));
   }

   ClusterJavaSerializationMarshaller(ClassAllowList allowList)
   {
      this(allowList, DEFAULT_WARN_SIZE);
   }

   ClusterJavaSerializationMarshaller(ClassAllowList allowList, int warnSize)
   {
      super(allowList);
      this.allowList = allowList;
      this.warnSize = warnSize;
   }

   /**
    * Keeps the platform's list only: an allow-list of an Infinispan configuration is refused when the configuration is
    * loaded ({@link Utils#configureSerialization}).
    */
   @Override
   public void initialize(ClassAllowList classAllowList)
   {
      // Nothing to do
   }

   @Override
   protected ByteBuffer objectToBuffer(Object o, int estimatedSize) throws IOException
   {
      LazyByteArrayOutputStream output = write(o, null, () -> describe(o));
      return ByteBufferImpl.create(output.getRawBuffer(), 0, output.size());
   }

   /**
    * Marshals a value whose throwables off the list are each replaced by the one the given function builds from it: a
    * failure travels with the types of its chain the other nodes accept, a cause they would refuse described instead.
    *
    * @param o the value
    * @param replacement builds the throwable that stands for a throwable off the list, of a class on the list
    * @return the marshalled value
    * @throws IOException when the value holds a class off the list that is not a throwable
    */
   public byte[] objectToByteBuffer(Object o, UnaryOperator<Throwable> replacement) throws IOException
   {
      return objectToByteBuffer(o, replacement, () -> describe(o));
   }

   /**
    * Marshals a value as {@link #objectToByteBuffer(Object, UnaryOperator)} does, naming it in the warning a write
    * larger than the size of {@link #WARN_SIZE_PROPERTY} logs.
    *
    * @param o the value
    * @param replacement builds the throwable that stands for a throwable off the list, or <code>null</code> to refuse
    *          it as any other class off the list
    * @param source names the value in the warning, a request of a command for example
    * @return the marshalled value
    * @throws IOException when the value holds a class off the list that is not replaced
    */
   public byte[] objectToByteBuffer(Object o, UnaryOperator<Throwable> replacement, Supplier<String> source)
                                                                                                          throws IOException
   {
      LazyByteArrayOutputStream output = write(o, replacement, source);
      return Arrays.copyOf(output.getRawBuffer(), output.size());
   }

   @Override
   public Object objectFromByteBuffer(byte[] buf, int offset, int length) throws IOException, ClassNotFoundException
   {
      try
      {
         return super.objectFromByteBuffer(buf, offset, length);
      }
      catch (CacheException e)
      {
         // The value of another node holds a class this node refuses: their lists differ
         warnRefusal(e.getMessage());
         throw e;
      }
   }

   /**
    * Logs a refusal the first time it happens.
    *
    * @param refusal the class refused, or the message that names it
    * @return true when the refusal was logged, false when it already was
    */
   static boolean warnRefusal(String refusal)
   {
      if (!REFUSALS.add(String.valueOf(refusal)))
      {
         return false;
      }
      LOG.warn("Refused by the cluster serialization allow-list: " + refusal + ". Add the class to the '"
         + ClusterAllowList.ALLOW_LIST_PROPERTY + "' property of every node if it is meant to travel");
      return true;
   }

   /**
    * Logs a write larger than the size of {@link #WARN_SIZE_PROPERTY} the first time its source writes one: the write
    * proceeds. Called after each write, which a subclass may observe.
    *
    * @param size the size of the write, in bytes
    * @param source names what is written
    * @return true when the write was logged, false when it is within the size, the warning is off, or its source was
    *         already logged
    */
   protected boolean warnIfOversized(int size, Supplier<String> source)
   {
      if (warnSize <= 0 || size <= warnSize)
      {
         return false;
      }
      String written = source.get();
      if (!OVERSIZES.add(written))
      {
         return false;
      }
      LOG.warn("A cluster write of {} bytes exceeds {} bytes: {}. The write proceeds; this is logged once for it, and the"
         + " '{}' property sets the size, 0 turning the warning off", size, warnSize, written, WARN_SIZE_PROPERTY);
      return true;
   }

   /**
    * @param value the value of {@link #WARN_SIZE_PROPERTY}
    * @return the size it sets, the default when it is not set or is not a number
    */
   static int warnSize(String value)
   {
      if (value == null || value.isBlank())
      {
         return DEFAULT_WARN_SIZE;
      }
      try
      {
         return Integer.parseInt(value.trim());
      }
      catch (NumberFormatException e)
      {
         LOG.warn("The '{}' property is not a number of bytes: {}. The default, {}, applies", WARN_SIZE_PROPERTY, value,
                  DEFAULT_WARN_SIZE);
         return DEFAULT_WARN_SIZE;
      }
   }

   private static String describe(Object o)
   {
      return o == null ? "null" : "an object of class " + o.getClass().getName();
   }

   private LazyByteArrayOutputStream write(Object o, UnaryOperator<Throwable> replacement, Supplier<String> source)
                                                                                                          throws IOException
   {
      LazyByteArrayOutputStream output = new LazyByteArrayOutputStream();
      try (ObjectOutputStream objectOutput = new AllowListObjectOutputStream(output, allowList, replacement))
      {
         objectOutput.writeObject(o);
      }
      warnIfOversized(output.size(), source);
      return output;
   }
}
