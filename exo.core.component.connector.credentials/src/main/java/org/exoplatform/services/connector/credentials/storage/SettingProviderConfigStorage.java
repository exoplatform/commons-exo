/*
 * Copyright (C) 2026 eXo Platform SAS.
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
package org.exoplatform.services.connector.credentials.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import org.springframework.stereotype.Service;

import io.meeds.common.ContainerTransactional;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsContext;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigField;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsProvider;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.ConnectorProviderConfigStorage;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsConfigFieldType;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

/**
 * Annotated {@code @Service} rather than {@code @Component} for the reason
 * {@link ConnectorCredentialsService} carries the same note: the connector add-ons that
 * inject this storage live in other WARs, and only {@code @Service} makes a bean visible
 * across the Spring contexts the kernel bridge merges.
 */
@Service
public class SettingProviderConfigStorage implements ConnectorProviderConfigStorage {

   /** Refused: a value under a key the provider's descriptor does not declare. */
   public static final String                UNKNOWN_FIELD = "connector.credentials.unknownConfigurationField";

   /** Refused: a field the descriptor marks required, with nothing in it. */
   public static final String                MISSING_FIELD = "connector.credentials.missingConfigurationField";

   /** Refused: a value outside the admitted list of a CHOICE field. */
   public static final String                INVALID_VALUE = "connector.credentials.invalidConfigurationValue";

   /** Refused: the keystore the codec needs is unusable, so no secret can be handled. */
   public static final String                CODEC_FAILURE = "connector.credentials.configurationCodecFailure";

   /**
    * One scope for every provider's configuration. The provider name is in the key
    * rather than the scope id: a scope per provider would make "read everything stored
    * for this connector" a loop over the registered providers, and that loop would go
    * stale the day a provider is unregistered while its settings remain.
    */
   private static final Scope                SCOPE         = Scope.APPLICATION.id("connectorCredentials");

   private final SettingService              settingService;

   private final CodecInitializer            codecInitializer;

   private final ConnectorCredentialsService connectorCredentialsService;

   public SettingProviderConfigStorage(SettingService settingService,
                                       CodecInitializer codecInitializer,
                                       ConnectorCredentialsService connectorCredentialsService) {
      this.settingService = settingService;
      this.codecInitializer = codecInitializer;
      this.connectorCredentialsService = connectorCredentialsService;
   }

   @Override
   public void validate(ConnectorCredentialsContext context, Map<String, String> values) throws ConnectorCredentialsException {
      List<ConnectorCredentialsConfigField> fields = describedFields(context);
      // The same retention rule store() applies, and necessarily so: a caller validates
      // to decide whether to write at all, so a validate stricter than the store it
      // guards would refuse a configuration the store would have taken. On a connector
      // that does not exist yet nothing is stored, nothing is retained, and a blank
      // required secret is simply missing - which is the first-save case.
      check(fields, values, retainedFields(context, fields, values));
   }

   /**
    * The secret fields whose stored value is being kept: typed SECRET, left blank by
    * the caller, and already holding something.
    *
    * @param context the connector the configuration belongs to
    * @param fields the provider's descriptors
    * @param values what was posted
    * @return the keys an empty value must not be read as an absent one
    */
   private Set<String> retainedFields(ConnectorCredentialsContext context,
                                      List<ConnectorCredentialsConfigField> fields,
                                      Map<String, String> values) {
      return fields.stream()
                   .filter(this::isSecret)
                   .filter(field -> StringUtils.isBlank(values.get(field.getKey())))
                   .filter(field -> storedValue(context, field.getKey()) != null)
                   .map(ConnectorCredentialsConfigField::getKey)
                   .collect(Collectors.toSet());
   }

   /**
    * The descriptor checks, shared by validate and store.
    *
    * @param fields the provider's descriptors
    * @param values what was posted
    * @param retainedFields keys whose stored value is being kept, so an empty posted
    *          value is not the absence of one
    * @throws ConnectorCredentialsException with the message code of the first refusal
    */
   private void check(List<ConnectorCredentialsConfigField> fields,
                      Map<String, String> values,
                      Set<String> retainedFields) throws ConnectorCredentialsException {
      for (String key : values.keySet()) {
         if (fields.stream().noneMatch(field -> field.getKey().equals(key))) {
            throw new ConnectorCredentialsException(UNKNOWN_FIELD);
         }
      }
      for (ConnectorCredentialsConfigField field : fields) {
         if (retainedFields.contains(field.getKey())) {
            continue;
         }
         String value = values.get(field.getKey());
         if (field.isRequired() && StringUtils.isBlank(value)) {
            throw new ConnectorCredentialsException(MISSING_FIELD);
         }
         if (StringUtils.isNotBlank(value) && !field.getOptions().isEmpty() && !field.getOptions().contains(value)) {
            throw new ConnectorCredentialsException(INVALID_VALUE);
         }
      }
   }

   /**
    * Validates the whole configuration before writing anything of it, so a refused
    * value never leaves half a configuration behind.
    * <p>
    * An empty value means three different things, by design: on a SECRET that already
    * has something stored it means "unchanged" (the form never received it, so it
    * cannot post it back); on a required field with nothing stored it is the first
    * save, and missing; on an optional field it is an erasure, and the entry goes.
    * <p>
    * Transactional because one configuration is several setting entries: a failure on
    * the second field would otherwise leave a login stored against a secret that never
    * was.
    */
   @Override
   @ContainerTransactional
   public void store(ConnectorCredentialsContext context, Map<String, String> values) throws ConnectorCredentialsException {
      List<ConnectorCredentialsConfigField> fields = describedFields(context);
      Set<String> retainedFields = retainedFields(context, fields, values);
      check(fields, values, retainedFields);
      Map<String, String> retained = new LinkedHashMap<>();
      for (ConnectorCredentialsConfigField field : fields) {
         if (retainedFields.contains(field.getKey())) {
            continue;
         }
         String value = values.get(field.getKey());
         retained.put(field.getKey(), isSecret(field) ? encode(value) : value);
      }
      for (Map.Entry<String, String> entry : retained.entrySet()) {
         String key = keyOf(context, entry.getKey());
         if (StringUtils.isBlank(entry.getValue())) {
            settingService.remove(Context.GLOBAL, SCOPE, key);
         } else {
            settingService.set(Context.GLOBAL, SCOPE, key, SettingValue.create(entry.getValue()));
         }
      }
   }

   /**
    * One entry per field, so a screen that changes the login alone rewrites the login
    * alone - and so the encrypted value never shares a row with a clear one.
    *
    * @param context the connector the configuration belongs to
    * @param fieldKey the descriptor key
    * @return the setting key, unique per (provider, kind, connector, field)
    */
   private String keyOf(ConnectorCredentialsContext context, String fieldKey) {
      return context.getConnectorCredentialsProviderName() + "/" + context.getConnectorKind() + "/"
          + context.getConnectorId() + "/" + fieldKey;
   }

   /**
    * The descriptor's type is what decides encryption - never a list kept beside it,
    * which would let a field be secret for the form and clear for the storage.
    *
    * @param field the descriptor
    * @return true when the value must go through the codec
    */
   private boolean isSecret(ConnectorCredentialsConfigField field) {
      return field.getType() == ConnectorCredentialsConfigFieldType.SECRET;
   }

   private String encode(String value) throws ConnectorCredentialsException {
      try {
         return value == null ? null : codecInitializer.getCodec().encode(value);
      } catch (TokenServiceInitializationException e) {
         throw new ConnectorCredentialsException(CODEC_FAILURE, e);
      }
   }

   private String decode(String value) throws ConnectorCredentialsException {
      try {
         return value == null ? null : codecInitializer.getCodec().decode(value);
      } catch (TokenServiceInitializationException e) {
         throw new ConnectorCredentialsException(CODEC_FAILURE, e);
      }
   }

   /**
    * The stored value of one field, or null when nothing was ever written for it.
    *
    * @param context the connector the configuration belongs to
    * @param fieldKey the descriptor key
    * @return the raw stored value - still encrypted for a SECRET field
    */
   private String storedValue(ConnectorCredentialsContext context, String fieldKey) {
      SettingValue<?> stored = settingService.get(Context.GLOBAL, SCOPE, keyOf(context, fieldKey));
      return stored == null || stored.getValue() == null ? null : String.valueOf(stored.getValue());
   }

   /**
    * The fields the context's provider declares - the only vocabulary a configuration
    * may use, and the source of which values are encrypted.
    *
    * @param context whose configuration is being read or written
    * @return the descriptors, empty when the provider declares none
    */
   private List<ConnectorCredentialsConfigField> describedFields(ConnectorCredentialsContext context) {
      return connectorCredentialsService.getProviders()
                                        .stream()
                                        .filter(provider -> provider.getName()
                                                                    .equals(context.getConnectorCredentialsProviderName()))
                                        .map(ConnectorCredentialsProvider::getConfigurationFields)
                                        .findFirst()
                                        .orElseGet(List::of);
   }

   @Override
   public Map<String, String> readDecrypted(ConnectorCredentialsContext context) throws ConnectorCredentialsException {
      Map<String, String> values = new LinkedHashMap<>();
      for (ConnectorCredentialsConfigField field : describedFields(context)) {
         String stored = storedValue(context, field.getKey());
         if (stored != null) {
            values.put(field.getKey(), isSecret(field) ? decode(stored) : stored);
         }
      }
      return values;
   }

   @Override
   public Map<String, String> readWithoutSecrets(ConnectorCredentialsContext context) {
      Map<String, String> values = new LinkedHashMap<>();
      for (ConnectorCredentialsConfigField field : describedFields(context)) {
         if (isSecret(field)) {
            continue;
         }
         String stored = storedValue(context, field.getKey());
         if (stored != null) {
            values.put(field.getKey(), stored);
         }
      }
      return values;
   }

   /**
    * Field by field rather than through the scope-wide remove: the scope holds every
    * connector's configuration, and one connector's removal must not touch the others.
    * <p>
    * Transactional for the write's reason in reverse: a half-removed configuration
    * leaves a technical secret behind that nobody administers any more.
    */
   @Override
   @ContainerTransactional
   public void delete(ConnectorCredentialsContext context) {
      for (ConnectorCredentialsConfigField field : describedFields(context)) {
         settingService.remove(Context.GLOBAL, SCOPE, keyOf(context, field.getKey()));
      }
   }

}
