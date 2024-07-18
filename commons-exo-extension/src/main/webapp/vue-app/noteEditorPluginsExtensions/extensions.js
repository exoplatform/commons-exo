/*
 * Copyright (C) 2024 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */

export function init() {
  if (eXo.ecm) {
    CKEDITOR.plugins.addExternal('uploadImage', '/eXoWCMResources/eXoPlugins/uploadImage/', 'plugin.js');
    CKEDITOR.plugins.addExternal('selectImage', '/eXoWCMResources/eXoPlugins/selectImage/', 'plugin.js');

    extensionRegistry.registerExtension('WYSIWYGPlugins', 'image', {
      id: 'selectImage',
      extraPlugin: 'selectImage',
      extraToolbarItem: 'selectImage',
      rank: 20,
    });
  }
}
