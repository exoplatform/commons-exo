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

import org.infinispan.commons.configuration.ClassAllowList;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.commons.io.ByteBufferImpl;
import org.infinispan.commons.io.LazyByteArrayOutputStream;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;

/**
 * The marshaller of the cached keys and values: Java serialization, which accepts on read only the classes of the
 * {@link ClusterAllowList}. It refuses as well to write a class off the list, so that a value the other nodes would
 * refuse fails on the node that writes it, before anything is sent.
 */
public class ClusterJavaSerializationMarshaller extends JavaSerializationMarshaller
{
   private final ClassAllowList allowList;

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
      LazyByteArrayOutputStream output = new LazyByteArrayOutputStream();
      try (ObjectOutputStream objectOutput = new AllowListObjectOutputStream(output, allowList))
      {
         objectOutput.writeObject(o);
      }
      return ByteBufferImpl.create(output.getRawBuffer(), 0, output.size());
   }

   /**
    * Checks the descriptor of each class written, its serializable superclasses included.
    */
   private static final class AllowListObjectOutputStream extends ObjectOutputStream
   {
      private final ClassAllowList allowList;

      private boolean refused;

      private AllowListObjectOutputStream(OutputStream output, ClassAllowList allowList) throws IOException
      {
         super(output);
         this.allowList = allowList;
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
            throw new InvalidClassException(type.getName(), "not on the cluster serialization allow-list: add it to the '"
               + ClusterAllowList.ALLOW_LIST_PROPERTY + "' property of every node");
         }
      }
   }
}
