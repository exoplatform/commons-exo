<!--
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
-->
<template>
  <div v-if="fields.length">
    <template v-for="field in fields">
      <v-label :key="`${field.key}-label`" :for="idOf(field)">
        {{ $t(field.labelKey) }}
      </v-label>
      <!-- A select without the text field's border-box-sizing/width-auto pair: those
           come from the platform's text-field standard, and on a select width-auto
           changes how v-select__selections shares its room, leaving a gap between the
           chosen label and the arrow. No v-select in the platform carries them. -->
      <v-select
        v-if="field.type === 'CHOICE'"
        :id="idOf(field)"
        :key="field.key"
        :value="configuration[field.key]"
        :items="optionsOf(field)"
        :hint="hintOf(field)"
        :rules="rulesOf(field)"
        :name="idOf(field)"
        class="pt-0 mt-2 mb-3"
        persistent-hint
        outlined
        dense
        @change="update(field.key, $event)" />
      <v-text-field
        v-else
        :id="idOf(field)"
        :key="field.key"
        :value="configuration[field.key]"
        :placeholder="placeholderOf(field)"
        :hint="hintOf(field)"
        :rules="rulesOf(field)"
        :name="idOf(field)"
        :type="typeOf(field)"
        :append-icon="appendIconOf(field)"
        :autocomplete="field.type === 'SECRET' && 'off' || null"
        class="border-box-sizing width-auto pt-0 mt-2 mb-3"
        persistent-hint
        outlined
        dense
        @click:append="reveal(field.key)"
        @input="update(field.key, $event)" />
    </template>
  </div>
</template>

<script>
export default {
  props: {
    /**
     * The fields the selected provider describes, as GET /credentials-providers
     * answered them. The component renders what it is given and knows no provider:
     * adding one never reopens this file.
     */
    fields: {
      type: Array,
      default: () => [],
    },
    /** The values being edited, keyed by field key. Bound with v-model. */
    value: {
      type: Object,
      default: () => ({}),
    },
    /**
     * Whether this entity already has secrets stored. A required SECRET left empty is
     * then valid and means "unchanged": the form never receives a stored secret, so it
     * cannot post one back, and demanding it again on every edit would force the
     * administrator to retype the password to rename a connector.
     */
    secretsStored: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      /**
       * Which secret fields the administrator asked to see. Kept here and never in the
       * saved values: it is a display state, and a secret that travels back and forth
       * for the sake of an eye icon is a secret with one more place to leak.
       */
      revealed: {},
    };
  },
  computed: {
    configuration() {
      return this.value || {};
    },
    /**
     * Whether every required field the provider describes carries a value. Emitted
     * rather than exposed, so the host drawer can disable its save button without
     * knowing a single thing about which fields the provider asked for.
     *
     * @returns {boolean} true when nothing required is missing
     */
    valid() {
      return this.fields.every(field => {
        if (!field.required) {
          return true;
        }
        if (field.type === 'SECRET' && this.secretsStored) {
          return true;
        }
        const fieldValue = this.configuration[field.key];
        return !!fieldValue && !!String(fieldValue).trim();
      });
    },
  },
  watch: {
    valid: {
      immediate: true,
      handler(isValid) {
        this.$emit('valid', isValid);
      },
    },
  },
  methods: {
    /**
     * The field's DOM id, which its label points at with `for` - the pairing that
     * makes the label announce the field to a screen reader. Prefixed, because two
     * providers can describe a field of the same key on one page.
     *
     * @param {object} field the field descriptor
     * @returns {string} the id shared by the label, the input and its name
     */
    idOf(field) {
      return `providerConfig-${field.key}`;
    },
    /**
     * The input's type: a SECRET is masked until the administrator reveals it.
     *
     * @param {object} field the field descriptor
     * @returns {string} an input type
     */
    typeOf(field) {
      return field.type === 'SECRET' && !this.revealed[field.key] && 'password' || 'text';
    },
    /**
     * The eye that reveals a secret, and nothing on any other field.
     *
     * @param {object} field the field descriptor
     * @returns {string} an icon name, or null
     */
    appendIconOf(field) {
      return field.type === 'SECRET' && (this.revealed[field.key] && 'fa-eye-slash' || 'fa-eye') || null;
    },
    /**
     * A text field's placeholder, worded in the bundle under <labelKey>.placeholder.
     * Derived from the label key rather than declared in the descriptor - the same
     * convention the options below follow - and simply absent when the bundle has
     * nothing to say, which is why the key's existence is tested rather than
     * translated blindly.
     * <p>
     * Text fields only. A placeholder on a v-select keeps Vuetify's inner search input
     * sized inside the selection slot, which steals the room the selected label needs:
     * the label renders truncated with an ellipsis and a caret sits beside it. No
     * v-select in this platform carries one.
     *
     * @param {object} field the field descriptor
     * @returns {string} the placeholder, or null when the bundle declares none
     */
    placeholderOf(field) {
      const key = `${field.labelKey}.placeholder`;
      return this.$te(key) && this.$t(key) || null;
    },
    /**
     * The help line under a field, when its descriptor names one.
     *
     * Handed to the input as its own hint rather than drawn in a div beside it: the
     * input puts it in its v-messages slot, which carries the same inner padding as
     * the text above it and no margin of its own. A div outside the input sits flush
     * against the container instead, so it reads as indented differently from the
     * field it describes, and its margin adds to the field's own.
     *
     * @param {object} field the field descriptor
     * @returns {string} the help text, or null
     */
    hintOf(field) {
      return field.helpKey && this.$te(field.helpKey) && this.$t(field.helpKey) || null;
    },
    /**
     * A closed list's option labels are derived, not declared: the descriptor carries
     * the admitted values only, and their wording lives in the bundle under
     * <labelKey>.option.<value>. One convention instead of a second descriptor type.
     *
     * @param {object} field the CHOICE field descriptor
     * @returns {Array} the value/text pairs the select renders
     */
    optionsOf(field) {
      return (field.options || []).map(option => {
        const key = `${field.labelKey}.option.${option}`;
        return {
          value: option,
          text: this.$te(key) && this.$t(key) || option,
        };
      });
    },
    rulesOf(field) {
      return field.required && [v => !!v || this.$t('credentialsProviderConfig.field.required')] || [];
    },
    reveal(key) {
      this.$set(this.revealed, key, !this.revealed[key]);
    },
    /**
     * Emits the whole map rather than mutating the prop: the parent owns the values,
     * and a mutated prop is the finding this org's frontend norm names explicitly.
     *
     * @param {string} key the field whose value changed
     * @param {string} fieldValue the value the administrator typed or picked
     * @returns {void}
     */
    update(key, fieldValue) {
      this.$emit('input', {...this.configuration, [key]: fieldValue});
    },
  },
};
</script>
