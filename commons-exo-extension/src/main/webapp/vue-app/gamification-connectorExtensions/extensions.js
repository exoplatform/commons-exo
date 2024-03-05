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
  extensionRegistry.registerExtension('engagementCenterConnectors', 'connector-extensions', {
    id: 'exoplatform',
    name: 'exoplatform',
    image: '/commons-exo-extension/images/connector/exo.png',
    title: 'eXo Platform',
    description: 'gamification.admin.exoplatform.label.description',
    rank: 11,
    defaultConnector: true,
    init: () => {
      const lang = window.eXo?.env?.portal?.language || 'en';
      const url = `${eXo.env.portal.context}/${eXo.env.portal.rest}/i18n/bundle/locale.portlet.exoPlatformGamificationConnector-${lang}.json`;
      return exoi18n.loadLanguageAsync(lang, url);
    }
  });
}
