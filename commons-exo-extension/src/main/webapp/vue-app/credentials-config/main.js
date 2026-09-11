/*
 * Copyright (C) 2026 eXo Platform SAS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */

import './initComponents.js';

import * as credentialsProviderService from './services.js';

// This module carries the keys its own descriptors name - the provider labels, the
// field labels, and the codes its refusals travel as - so it loads its own bundle
// instead of asking every host to. The platform's i18n wrapper holds ONE shared
// VueI18n instance and merges into it (vue-i18n-wrapper.js, mergeLocaleMessage), so
// the keys become resolvable for every app on the page. A host add-on therefore
// declares the module and nothing else: no bundle name, and above all no path into
// this webapp's context, which it has no business knowing.
const credentialsConfigLang = eXo?.env?.portal?.language || 'en';
exoi18n.loadLanguageAsync(credentialsConfigLang,
  `/commons-exo-extension/i18n/locale.portlet.credentialsProviderConfig?lang=${credentialsConfigLang}`);

if (!Vue.prototype.$credentialsProviderService) {
  window.Object.defineProperty(Vue.prototype, '$credentialsProviderService', {
    value: credentialsProviderService,
  });
}
