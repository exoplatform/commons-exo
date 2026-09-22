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
  <exo-drawer
    ref="drawer"
    v-model="opened"
    :loading="saving"
    right
    @closed="closed">
    <template #title>
      <span>{{ $t('managedConnector.drawer.title') }}</span>
    </template>
    <template v-if="opened" #content>
      <div class="mx-5 mt-5">
        <div class="text-subtitle mb-6">
          {{ $t('managedConnector.drawer.description') }}
        </div>
        <!--
          The empty state is a sentence and a way out, never a disabled switch in
          the row behind. Two sentences, in fact, because the two emptinesses have
          different remedies: with no connector at all the administrator declares
          one; with connectors that all ask their users for something, they need
          one configured with a provider that does not - and saying "no candidate"
          alone would send them looking for the wrong thing.
        -->
        <div v-if="!eligibleCandidates.length">
          <div class="text-subtitle mb-4">
            {{ $t(candidates.length ? 'managedConnector.drawer.noneEligible' : 'managedConnector.drawer.noCandidates') }}
          </div>
          <slot name="empty-action"></slot>
        </div>
        <div v-else>
          <div class="font-weight-bold mb-2">
            {{ $t('managedConnector.drawer.connectorLabel') }}
          </div>
          <!--
            Only the candidates the instance could actually attach everybody to:
            active, and configured with a provider that asks the user for nothing.
            The rest are absent rather than greyed - greying invites the question
            "why not this one?" on a screen whose answer is a different screen.
            Even a single candidate is rendered and preselected rather than skipped:
            the administrator should read the name at the moment they commit.
          -->
          <v-radio-group
            v-model="selectedId"
            class="mt-0 pt-0"
            hide-details>
            <v-radio
              v-for="candidate in eligibleCandidates"
              :key="candidate.id"
              :value="candidate.id"
              class="mb-4">
              <template #label>
                <div class="d-flex align-center min-width-0">
                  <div class="flex-grow-0 flex-shrink-0 me-3">
                    <slot name="icon" :candidate="candidate">
                      <v-icon size="24">fa-server</v-icon>
                    </slot>
                  </div>
                  <div class="min-width-0 text-start">
                    <div class="text-truncate">{{ candidate.name }}</div>
                    <div v-if="candidate.subtitle" class="text-subtitle text-truncate">{{ candidate.subtitle }}</div>
                  </div>
                </div>
              </template>
            </v-radio>
          </v-radio-group>
          <div class="font-weight-bold mt-4 mb-2">
            {{ $t('managedConnector.drawer.excludedGroupsLabel') }}
          </div>
          <div class="text-subtitle mb-2">
            {{ $t('managedConnector.drawer.excludedGroupsHelp') }}
          </div>
          <!--
            The platform's own population picker, the one every administration
            screen uses (space permissions, categories, user memberships). Groups
            and spaces - a space is a group too, /spaces/<name>, and its members
            are read the same way - every group for an administrator, several at
            once. Never users: an exclusion names a population, and the login
            resolution compares group ids only.
          -->
          <exo-identity-suggester
            v-model="excludedGroupEntries"
            :labels="groupLabels"
            :search-options="{filterType: 'all'}"
            name="managedConnectorExcludedGroups"
            include-groups
            include-spaces
            all-groups-for-admin
            multiple />
        </div>
        <v-alert
          v-if="errorMessage"
          type="error"
          class="mt-4"
          dense
          text>
          {{ errorMessage }}
        </v-alert>
      </div>
    </template>
    <template #footer>
      <div class="d-flex flex-column">
        <!--
          Not an ordinary setting, and the screen says so where the click happens:
          applying attaches a whole population, one user at a time as they log in.
          A checkbox that looked like any other would be read like any other.
        -->
        <div v-if="selectedCandidate" class="text-subtitle mb-3">
          {{ $t('managedConnector.drawer.consequence', {0: selectedCandidate.name}) }}
        </div>
        <div class="d-flex">
          <v-spacer />
          <v-btn
            class="btn"
            @click="close">
            {{ $t('managedConnector.drawer.cancel') }}
          </v-btn>
          <v-btn
            :disabled="saving || !selectedId"
            class="btn btn-primary ms-5"
            @click="apply">
            {{ $t('managedConnector.drawer.apply') }}
          </v-btn>
        </div>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
/**
 * The drawer through which an administrator designates the connector of a kind
 * for the whole instance, minus the groups it must not reach.
 *
 * Shared by the connector add-ons (calendars, mailboxes): each hands in its rows
 * as candidates and a function that performs its own REST call, and reads back
 * what the platform now holds. Nothing here knows a kind, a URL or a provider -
 * the eligibility rule is read from what the add-on's connection-requirements
 * endpoint answered, so a new provider changes nothing in this file.
 */
export default {
  props: {
    /**
     * The connectors the add-on declares, as {id, name, subtitle, active,
     * providerName}. The drawer keeps the ones that can be designated.
     */
    candidates: {
      type: Array,
      default: () => [],
    },
    /**
     * Provider name to whether that provider asks the user for something, as the
     * add-on's connection-requirements endpoint answered. Only a provider that
     * answers false, explicitly, makes a candidate eligible: an absent provider
     * is unknown, and unknown is not "asks nothing".
     */
    connectionRequirements: {
      type: Object,
      default: () => ({}),
    },
    /**
     * Performs the add-on's own save, (connectorId, excludedGroups) => Promise of
     * the mode now in force. A rejection whose message is a message code keeps the
     * drawer open on the choice, carrying the reason. The REST call stays in the
     * add-on: it is the add-on's endpoint that checks the row exists and is its.
     */
    save: {
      type: Function,
      required: true,
    },
  },
  data: () => ({
    opened: false,
    saving: false,
    /**
     * Whether Apply actually stored something during this opening. It is what
     * tells a close apart from a commit: the drawer's `closed` event fires in
     * both cases, and only one of them must leave the host's switch on.
     */
    applied: false,
    selectedId: null,
    /** The picker's items; what is stored is their groupId, nothing else. */
    excludedGroupEntries: [],
    errorMessage: '',
  }),
  computed: {
    eligibleCandidates() {
      return this.candidates.filter(candidate => candidate.active
        && this.connectionRequirements[candidate.providerName] === false);
    },
    selectedCandidate() {
      return this.eligibleCandidates.find(candidate => candidate.id === this.selectedId) || null;
    },
    excludedGroupIds() {
      return (this.excludedGroupEntries || []).map(entry => entry.groupId).filter(groupId => !!groupId);
    },
    groupLabels() {
      return {
        placeholder: this.$t('managedConnector.drawer.excludedGroupsPlaceholder'),
        noDataLabel: this.$t('managedConnector.drawer.excludedGroupsNoData'),
      };
    },
  },
  methods: {
    /**
     * Opens the drawer, preselected on what is in force.
     *
     * Preselected on the designated connector, else - the ordinary case of
     * switching the mode on - on the only candidate when there is exactly one.
     * The excluded groups are rebuilt into picker items from their stored ids, so
     * the administrator sees names and not paths; a group the platform cannot
     * resolve is still shown, by its id, rather than silently dropped from a list
     * it would then be saved without.
     *
     * @param {Object} managed the mode in force, {connectorId, excludedGroups};
     *          absent when off
     * @returns {Promise} resolves once the groups are resolved
     */
    open(managed) {
      const inForce = managed || {};
      this.applied = false;
      this.errorMessage = '';
      this.selectedId = inForce.connectorId
        || (this.eligibleCandidates.length === 1 && this.eligibleCandidates[0].id)
        || null;
      this.excludedGroupEntries = [];
      this.opened = true;
      this.$refs.drawer.open();
      return Promise.all((inForce.excludedGroups || []).map(this.groupEntryOf))
        .then(entries => this.excludedGroupEntries = entries);
    },
    /**
     * A picker item for a stored group id, resolved by name when the platform
     * knows it and reduced to its id otherwise.
     *
     * A space is stored as its group, /spaces/<name>, and comes back as the
     * space item the picker would have produced - name and avatar - through
     * the space service; any other group through the identity service.
     *
     * @param {String} groupId the eXo group id, e.g. /platform/externals or /spaces/marketing
     * @returns {Promise<Object>} the item the picker renders
     */
    groupEntryOf(groupId) {
      const fallback = {
        id: `group:${groupId}`,
        remoteId: groupId,
        groupId,
        providerId: 'group',
        displayName: groupId,
        profile: {fullName: groupId, originalName: groupId},
      };
      if (groupId.indexOf('/spaces/') === 0) {
        if (!this.$spaceService || !this.$spaceService.getSpaceByGroupId) {
          return Promise.resolve(fallback);
        }
        return this.$spaceService.getSpaceByGroupId(groupId)
          .then(space => {
            if (!space) {
              return fallback;
            }
            return {
              id: `space:${space.prettyName}`,
              remoteId: space.prettyName,
              spaceId: space.id,
              groupId,
              providerId: 'space',
              displayName: space.displayName,
              profile: {
                fullName: space.displayName,
                originalName: space.shortName,
                avatarUrl: space.avatarUrl || `/portal/rest/v1/social/spaces/${space.prettyName}/avatar`,
              },
            };
          })
          .catch(() => fallback);
      }
      if (!this.$identityService || !this.$identityService.getIdentityByProviderIdAndRemoteId) {
        return Promise.resolve(fallback);
      }
      return this.$identityService.getIdentityByProviderIdAndRemoteId('group', groupId)
        .then(group => {
          const name = group && group.profile && group.profile.fullname || groupId;
          return {...fallback, displayName: name, profile: {fullName: name, originalName: name}};
        })
        .catch(() => fallback);
    },
    close() {
      this.opened = false;
      this.$refs.drawer.close();
    },
    /**
     * What a close that stored nothing owes the host: a `cancelled`, so that its
     * switch goes back to what the setting says rather than staying on for a
     * choice that was never made.
     *
     * @returns {void}
     */
    closed() {
      this.opened = false;
      if (!this.applied) {
        this.$emit('cancelled');
      }
    },
    /**
     * Stores the choice through the add-on's own save: this, and nothing before
     * it, is what turns managed mode on.
     *
     * What the platform answers is what the host then shows - a value it refused
     * must not sit on screen as though it had been accepted - and a refusal keeps
     * the drawer open on the choice, carrying the reason when the code is one the
     * bundles translate.
     *
     * @returns {Promise} resolves once the choice has been stored, or refused
     */
    apply() {
      if (!this.selectedId) {
        return Promise.resolve();
      }
      this.saving = true;
      this.errorMessage = '';
      return this.save(this.selectedId, this.excludedGroupIds)
        .then(managed => {
          this.applied = true;
          this.$emit('saved', managed);
          this.close();
        })
        .catch(error => {
          const code = error && error.message || '';
          this.errorMessage = code && this.$te(code) ? this.$t(code) : this.$t('managedConnector.drawer.saveFailed');
        })
        .finally(() => this.saving = false);
    },
  },
};
</script>
