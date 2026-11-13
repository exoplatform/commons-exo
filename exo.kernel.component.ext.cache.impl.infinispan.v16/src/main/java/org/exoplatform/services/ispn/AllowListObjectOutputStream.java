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
import java.util.function.UnaryOperator;

import org.infinispan.commons.configuration.ClassAllowList;

/**
 * The stream of {@link ClusterJavaSerializationMarshaller}: checks the descriptor of each class written, its
 * serializable superclasses included, and replaces a throwable off the list when a replacement is given.
 */
final class AllowListObjectOutputStream extends ObjectOutputStream
{
   private final ClassAllowList            allowList;

   private final UnaryOperator<Throwable> replacement;

   private boolean                         refused;

   AllowListObjectOutputStream(OutputStream output, ClassAllowList allowList, UnaryOperator<Throwable> replacement)
      throws IOException
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
         ClusterJavaSerializationMarshaller.warnRefusal(type.getName());
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
            ClusterJavaSerializationMarshaller.warnRefusal(refusedClass);
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
