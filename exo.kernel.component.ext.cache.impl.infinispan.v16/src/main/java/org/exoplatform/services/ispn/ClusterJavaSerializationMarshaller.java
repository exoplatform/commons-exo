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
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.infinispan.commons.CacheException;
import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.io.ByteBufferImpl;
import org.infinispan.commons.io.LazyByteArrayOutputStream;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The marshaller of the cached keys and values: Java serialization, which accepts on read only the classes of the
 * {@link ClusterAllowList}. It refuses as well to write a class off the list, so that a value the other nodes would
 * refuse fails on the node that writes it, before anything is sent. Each class refused is logged once, as a warning.
 */
public class ClusterJavaSerializationMarshaller extends JavaSerializationMarshaller
{
   private static final Log         LOG      = ExoLogger.getLogger(ClusterJavaSerializationMarshaller.class);

   private static final Set<String> REFUSALS = ConcurrentHashMap.newKeySet();

   private final ClassAllowList     allowList;

   /**
    * A marshaller applying the list of this node.
    */
   public ClusterJavaSerializationMarshaller()
   {
      this(ClusterAllowList.getInstance().getInfinispanAllowList());
   }

   ClusterJavaSerializationMarshaller(ClassAllowList allowList)
   {
      super(allowList);
      this.allowList = allowList;
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
      LazyByteArrayOutputStream output = write(o, null);
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
      LazyByteArrayOutputStream output = write(o, replacement);
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

   private LazyByteArrayOutputStream write(Object o, UnaryOperator<Throwable> replacement) throws IOException
   {
      LazyByteArrayOutputStream output = new LazyByteArrayOutputStream();
      try (ObjectOutputStream objectOutput = new AllowListObjectOutputStream(output, allowList, replacement))
      {
         objectOutput.writeObject(o);
      }
      return output;
   }

   /**
    * Checks the descriptor of each class written, its serializable superclasses included.
    */
   private static final class AllowListObjectOutputStream extends ObjectOutputStream
   {
      private final ClassAllowList            allowList;

      private final UnaryOperator<Throwable> replacement;

      private boolean                         refused;

      private AllowListObjectOutputStream(OutputStream output,
                                          ClassAllowList allowList,
                                          UnaryOperator<Throwable> replacement) throws IOException
      {
         super(output);
         this.allowList = allowList;
         this.replacement = replacement;
         enableReplaceObject(replacement != null);
      }

      /**
       * After a refusal, the stream writes the exception it throws, which is then not checked, so that the exception
       * names the refused class.
       */
      @Override
      protected void annotateClass(Class<?> type) throws IOException
      {
         if (!refused && !allowList.isSafeClass(type.getName()))
         {
            refused = true;
            warnRefusal(type.getName());
            throw new InvalidClassException(type.getName(), "not on the cluster serialization allow-list: add it to the '"
               + ClusterAllowList.ALLOW_LIST_PROPERTY + "' property of every node");
         }
      }

      @Override
      protected Object replaceObject(Object obj)
      {
         if (obj instanceof Throwable throwable)
         {
            String refusedClass = getRefusedClass(throwable.getClass());
            if (refusedClass != null)
            {
               warnRefusal(refusedClass);
               return replacement.apply(throwable);
            }
         }
         return obj;
      }

      private String getRefusedClass(Class<?> type)
      {
         for (Class<?> c = type; c != null && Serializable.class.isAssignableFrom(c); c = c.getSuperclass())
         {
            if (!allowList.isSafeClass(c.getName()))
            {
               return c.getName();
            }
         }
         return null;
      }
   }
}
