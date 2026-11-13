# commons-exo

## Caches

The caches the extension declares in `commons-exo-extension/src/main/webapp/WEB-INF/conf/commons-exo/cache-configuration.xml`, each value with its default and the property that sets it. A distribution may set any of these properties otherwise, in its `configuration.properties` or in `exo.properties`.

- **Live time** is in seconds, on a single node and in a cluster alike; `-1` keeps an entry until it is evicted.
- **Cluster mode** applies in the `cluster` profile only: `asyncInvalidation` sends only the keys of what changes, `replication` sends the values, `local` keeps each node's entries to itself.
- **Max entries**: a full cache evicts by Infinispan 16's single eviction policy; the `exo.cache.<name>.strategy` values the file still carries are accepted and ignored.
- **Profile** names the profile that declares the cache, when it is not declared in every profile.

The table is generated from the configuration file: after a change there, run `mvn test -pl commons-exo-extension -Dtest=TestCacheConfigurationReadme -Dexo.cache.readme.update=true` and commit this file; the build fails while they differ.

<!-- caches:start -->
| Cache | Profile | Max entries | Live time (s) | Cluster mode |
|---|---|---|---|---|
| `analytics.queue` | analytics | 2000 (`analytics.queue.MaxNodes`) | -1 (`analytics.queue.TimeToLive`) | local (`exo.cache.analytics.queue.cacheMode`) |
| `commons.WebNotificationCache` |  | 100000 (`exo.cache.commons.WebNotificationCache.MaxNodes`) | 86400 (`exo.cache.commons.WebNotificationCache.TimeToLive`) | asyncInvalidation (`exo.cache.commons.WebNotificationCache.cacheMode`) |
| `commons.WebNotificationsCache` |  | 1000 (`exo.cache.commons.WebNotificationsCache.MaxNodes`) | 86400 (`exo.cache.commons.WebNotificationsCache.TimeToLive`) | asyncInvalidation (`exo.cache.commons.WebNotificationsCache.cacheMode`) |
| `commons.WebNotificationCountCache` |  | 1000 (`exo.cache.commons.WebNotificationCountCache.MaxNodes`) | 86400 (`exo.cache.commons.WebNotificationCountCache.TimeToLive`) | asyncInvalidation (`exo.cache.commons.WebNotificationCountCache.cacheMode`) |
| `commons.UserSettingService` |  | 500 (`exo.cache.commons.UserSettingService.MaxNodes`) | 86400 (`exo.cache.commons.UserSettingService.TimeToLive`) | asyncInvalidation (`exo.cache.commons.UserSettingService.cacheMode`) |
| `commons.UserStateService` |  | 1000 (`exo.cache.commons.UserStateService.MaxNodes`) | 86400 (`exo.cache.commons.UserStateService.TimeToLive`) | replication (`exo.cache.commons.UserStateService.cacheMode`) |
| `gamification.domain` | gamification | 1000 (`exo.cache.gamification.domain.MaxNodes`) | -1 (`exo.cache.gamification.domain.TimeToLive`) | asyncInvalidation (`exo.cache.gamification.domain.cacheMode`) |
| `gamification.rule` | gamification | 2000 (`exo.cache.gamification.rule.MaxNodes`) | -1 (`exo.cache.gamification.rule.TimeToLive`) | asyncInvalidation (`exo.cache.gamification.rule.cacheMode`) |
| `gamification.realization` | gamification | 5000 (`exo.cache.gamification.realization.MaxNodes`) | -1 (`exo.cache.gamification.realization.TimeToLive`) | asyncInvalidation (`exo.cache.gamification.realization.cacheMode`) |
| `wiki.PageRenderingCache` | notes | 1000 (`exo.cache.wiki.PageRenderingCache.MaxNodes`) | 86400 (`exo.cache.wiki.PageRenderingCache.TimeToLive`) | asyncInvalidation (`exo.cache.wiki.PageRenderingCache.cacheMode`) |
| `wiki.PageAttachmentCache` | notes | 1000 (`exo.cache.wiki.PageAttachmentCache.MaxNodes`) | 86400 (`exo.cache.wiki.PageAttachmentCache.TimeToLive`) | asyncInvalidation (`exo.cache.wiki.PageAttachmentCache.cacheMode`) |
| `perkstore.product` |  | 1000 (`exo.cache.perkstore.product.MaxNodes`) | -1 (`exo.cache.perkstore.product.TimeToLive`) | asyncInvalidation (`exo.cache.perkstore.product.cacheMode`) |
| `perkstore.order` |  | 2000 (`exo.cache.perkstore.order.MaxNodes`) | -1 (`exo.cache.perkstore.order.TimeToLive`) | asyncInvalidation (`exo.cache.perkstore.order.cacheMode`) |
| `portal.User` |  | 5000 (`exo.cache.portal.user.MaxNodes`) | 3600 (`exo.cache.portal.user.TimeToLive`) | asyncInvalidation (`exo.cache.portal.user.cacheMode`) |
| `portal.Profile` |  | 5000 (`exo.cache.portal.profile.MaxNodes`) | 3600 (`exo.cache.portal.profile.TimeToLive`) | asyncInvalidation (`exo.cache.portal.profile.cacheMode`) |
| `portal.Membership` |  | 5000 (`exo.cache.portal.membership.MaxNodes`) | 86400 (`exo.cache.portal.membership.TimeToLive`) | asyncInvalidation (`exo.cache.portal.membership.cacheMode`) |
| `portal.Role` |  | 5000 (`exo.cache.portal.role.MaxNodes`) | -1 (`exo.cache.portal.role.TimeToLive`) | asyncInvalidation (`exo.cache.portal.role.cacheMode`) |
| `portal.Group` |  | 5000 (`exo.cache.portal.group.MaxNodes`) | -1 (`exo.cache.portal.group.TimeToLive`) | asyncInvalidation (`exo.cache.portal.group.cacheMode`) |
| `portal.tokens` |  | 5000 (`exo.cache.portal.tokens.MaxNodes`) | 86400 (`exo.cache.portal.tokens.TimeToLive`) | asyncInvalidation (`exo.cache.portal.tokens.cacheMode`) |
| `portal.IdentityRegistry` |  | 5000 (`exo.cache.portal.IdentityRegistry.MaxNodes`) | 3600 (`exo.cache.portal.IdentityRegistry.TimeToLive`) | asyncInvalidation (`exo.cache.portal.IdentityRegistry.cacheMode`) |
| `portal.ConversationRegistry` |  | 5000 (`exo.cache.portal.ConversationRegistry.MaxNodes`) | 3600 (`exo.cache.portal.ConversationRegistry.TimeToLive`) | local (`exo.cache.portal.ConversationRegistry.cacheMode`) |
| `portal.ConversationRegistryStates` |  | 5000 (`exo.cache.portal.ConversationRegistryStates.MaxNodes`) | 3600 (`exo.cache.portal.ConversationRegistryStates.TimeToLive`) | local (`exo.cache.portal.ConversationRegistryStates.cacheMode`) |
| `commons.SettingService` |  | 100000 (`exo.cache.commons.SettingService.MaxNodes`) | 86400 (`exo.cache.commons.SettingService.TimeToLive`) | asyncInvalidation (`exo.cache.commons.SettingService`) |
| `portal.PortletPreferences` |  | 10000 (`exo.cache.portal.preferences.MaxNodes`) | 86400 (`exo.cache.portal.preferences.TimeToLive`) | asyncInvalidation (`exo.cache.portal.preferences.cacheMode`) |
| `portal.NavigationNode` |  | 5000 (`exo.cache.portal.mop.MaxNodes`) | 86400 (`exo.cache.portal.mop.TimeToLive`) | asyncInvalidation (`exo.cache.portal.mop.cacheMode`) |
| `portal.NavigationService` |  | 1000 (`exo.cache.portal.navigation.MaxNodes`) | 86400 (`exo.cache.portal.navigation.TimeToLive`) | asyncInvalidation (`exo.cache.portal.navigation.cacheMode`) |
| `portal.SiteService` |  | 1000 (`exo.cache.portal.site.MaxNodes`) | 86400 (`exo.cache.portal.site.TimeToLive`) | asyncInvalidation (`exo.cache.portal.site.cacheMode`) |
| `portal.SiteKeysByFilterService` |  | 1000 (`exo.cache.portal.site.MaxNodes`) | 86400 (`exo.cache.portal.site.TimeToLive`) | asyncInvalidation (`exo.cache.portal.site.cacheMode`) |
| `portal.DescriptionService` |  | 10000 (`exo.cache.portal.description.MaxNodes`) | 86400 (`exo.cache.portal.description.TimeToLive`) | asyncInvalidation (`exo.cache.portal.description.cacheMode`) |
| `portal.DescriptionLocales` |  | 5000 (`exo.cache.portal.DescriptionLocales.MaxNodes`) | 86400 (`exo.cache.portal.DescriptionLocales.TimeToLive`) | asyncInvalidation (`exo.cache.portal.DescriptionLocales.cacheMode`) |
| `portal.UIPortal` |  | 5000 (`exo.cache.portal.UIPortal.MaxNodes`) | 86400 (`exo.cache.portal.UIPortal.TimeToLive`) | asyncInvalidation (`exo.cache.portal.UIPortal.cacheMode`) |
| `portal.PageService` |  | 1000 (`exo.cache.portal.page.MaxNodes`) | 86400 (`exo.cache.portal.page.TimeToLive`) | asyncInvalidation (`exo.cache.portal.page.cacheMode`) |
| `portal.TemplateService` |  | 5000 (`exo.cache.portal.template.MaxNodes`) | -1 (`exo.cache.portal.template.TimeToLive`) | local (`exo.cache.portal.template.cacheMode`) |
| `portal.ResourceBundleData` |  | 1000 (`exo.cache.portal.ResourceBundleData.MaxNodes`) | -1 (`exo.cache.portal.ResourceBundleData.TimeToLive`) | local (`exo.cache.portal.ResourceBundleData.cacheMode`) |
| `social.IdentityCache` |  | 1100 (`exo.cache.social.IdentityCache.MaxNodes`) | 86400 (`exo.cache.social.IdentityCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.IdentityCache.cacheMode`) |
| `social.IdentityIndexCache` |  | 1100 (`exo.cache.social.IdentityIndexCache.MaxNodes`) | 86400 (`exo.cache.social.IdentityIndexCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.IdentityIndexCache.cacheMode`) |
| `social.ProfileCache` |  | 1100 (`exo.cache.social.ProfileCache.MaxNodes`) | 86400 (`exo.cache.social.ProfileCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.ProfileCache.cacheMode`) |
| `social.IdentitiesCountCache` |  | 1100 (`exo.cache.social.IdentitiesCountCache.MaxNodes`) | 86400 (`exo.cache.social.IdentitiesCountCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.IdentitiesCountCache.cacheMode`) |
| `social.IdentitiesCache` |  | 1100 (`exo.cache.social.IdentitiesCache.MaxNodes`) | 86400 (`exo.cache.social.IdentitiesCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.IdentitiesCache.cacheMode`) |
| `social.RelationshipCache` |  | 100000 (`exo.cache.social.RelationshipCache.MaxNodes`) | 86400 (`exo.cache.social.RelationshipCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.RelationshipCache.cacheMode`) |
| `social.RelationshipFromIdentityCache` |  | 100000 (`exo.cache.social.RelationshipFromIdentityCache.MaxNodes`) | 86400 (`exo.cache.social.RelationshipFromIdentityCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.RelationshipFromIdentityCache.cacheMode`) |
| `social.RelationshipsCountCache` |  | 10000 (`exo.cache.social.RelationshipsCountCache.MaxNodes`) | 86400 (`exo.cache.social.RelationshipsCountCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.RelationshipsCountCache.cacheMode`) |
| `social.RelationshipsCache` |  | 10000 (`exo.cache.social.RelationshipsCache.MaxNodes`) | 86400 (`exo.cache.social.RelationshipsCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.RelationshipsCache.cacheMode`) |
| `social.SuggestionsCache` |  | 1000 (`exo.cache.social.SuggestionsCache.MaxNodes`) | 86400 (`exo.cache.social.SuggestionsCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.SuggestionsCache.cacheMode`) |
| `social.ActivityCache` |  | 20000 (`exo.cache.social.ActivityCache.MaxNodes`) | 86400 (`exo.cache.social.ActivityCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.ActivityCache.cacheMode`) |
| `social.ActivitiesCountCache` |  | 20000 (`exo.cache.social.ActivitiesCountCache.MaxNodes`) | 86400 (`exo.cache.social.ActivitiesCountCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.ActivitiesCountCache.cacheMode`) |
| `social.ActivitiesCache` |  | 20000 (`exo.cache.social.ActivitiesCache.MaxNodes`) | 86400 (`exo.cache.social.ActivitiesCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.ActivitiesCache.cacheMode`) |
| `social.SpaceCache` |  | 100 (`exo.cache.social.SpaceCache.MaxNodes`) | 86400 (`exo.cache.social.SpaceCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.SpaceCache.cacheMode`) |
| `social.SpaceRefCache` |  | 100 (`exo.cache.social.SpaceRefCache.MaxNodes`) | 86400 (`exo.cache.social.SpaceRefCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.SpaceRefCache.cacheMode`) |
| `social.SpacesCountCache` |  | 4000 (`exo.cache.social.SpacesCountCache.MaxNodes`) | 86400 (`exo.cache.social.SpacesCountCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.SpacesCountCache.cacheMode`) |
| `social.SpacesCache` |  | 4000 (`exo.cache.social.SpacesCache.MaxNodes`) | 86400 (`exo.cache.social.SpacesCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.SpacesCache.cacheMode`) |
| `social.translation` |  | 5000 (`exo.cache.social.TranslationCache.MaxNodes`) | 86400 (`exo.cache.social.TranslationCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.TranslationCache.cacheMode`) |
| `social.linkSettings` |  | 5000 (`exo.cache.social.LinkSettingsCache.MaxNodes`) | 86400 (`exo.cache.social.LinkSettingsCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.TranslationCache.cacheMode`) |
| `social.cmsSetting` |  | 5000 (`exo.cache.social.cmsSettingCache.MaxNodes`) | 86400 (`exo.cache.social.cmsSettingCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.TranslationCache.cacheMode`) |
| `social.ProfileAvatar` |  | 300 (`exo.cache.social.ProfileAvatar.MaxNodes`) | 600 (`exo.cache.social.ProfileAvatar.TimeToLive`) | asyncInvalidation (`exo.cache.social.TranslationCache.cacheMode`) |
| `wallet.transaction` | wallet | 2000 (`exo.cache.wallet.transaction.MaxNodes`) | -1 (`exo.cache.wallet.transaction.TimeToLive`) | asyncInvalidation (`exo.cache.wallet.transaction.cacheMode`) |
| `wallet.account` | wallet | 2000 (`exo.cache.wallet.account.MaxNodes`) | -1 (`exo.cache.wallet.account.TimeToLive`) | asyncInvalidation (`exo.cache.wallet.account.cacheMode`) |
| `social.profileSettings` |  | 2000 (`exo.cache.social.profileSettings.MaxNodes`) | -1 (`exo.cache.social.profileSettings.TimeToLive`) | asyncInvalidation (`exo.cache.social.profileSettings.cacheMode`) |
| `social.profileSettingIdsByName` |  | 2000 (`exo.cache.social.profileSettingIdsByName.MaxNodes`) | -1 (`exo.cache.social.profileSettingIdsByName.TimeToLive`) | asyncInvalidation (`exo.cache.social.profileSettingIdsByName.cacheMode`) |
| `commons.WebNotificationCountByPluginCache` |  | 300 (`exo.cache.commons.WebNotificationCountByPluginCache.MaxNodes`) | 600 (`exo.cache.commons.WebNotificationCountByPluginCache.TimeToLive`) | asyncInvalidation (`exo.cache.commons.WebNotificationCountByPluginCache.cacheMode`) |
| `social.metadata` |  | 300 (`exo.cache.social.metadata.MaxNodes`) | 600 (`exo.cache.social.metadata.TimeToLive`) | asyncInvalidation (`exo.cache.social.metadata.cacheMode`) |
| `social.metadataKey` |  | 300 (`exo.cache.social.metadataKey.MaxNodes`) | 600 (`exo.cache.social.metadataKey.TimeToLive`) | asyncInvalidation (`exo.cache.social.metadataKey.cacheMode`) |
| `social.SpacesCountByTemplateCache` |  | 300 (`exo.cache.social.SpacesCountByTemplateCache.MaxNodes`) | 600 (`exo.cache.social.SpacesCountByTemplateCache.TimeToLive`) | asyncInvalidation (`exo.cache.social.SpacesCountByTemplateCache.cacheMode`) |
| `gamification.connectors` |  | 300 (`exo.cache.gamification.connectors.MaxNodes`) | 600 (`exo.cache.gamification.connectors.TimeToLive`) | asyncInvalidation (`exo.cache.gamification.connectors.cacheMode`) |
| `ai.agents.promptSettings` |  | 300 (`exo.cache.ai.agents.promptSettings.MaxNodes`) | 600 (`exo.cache.ai.agents.promptSettings.TimeToLive`) | asyncInvalidation (`exo.cache.ai.agents.promptSettings.cacheMode`) |
| `portal.PageKeyById` |  | 300 (`exo.cache.portal.PageKeyById.MaxNodes`) | 600 (`exo.cache.portal.PageKeyById.TimeToLive`) | local (`exo.cache.portal.PageKeyById.cacheMode`) |
| `portal.SiteKeyById` |  | 300 (`exo.cache.portal.SiteKeyById.MaxNodes`) | 600 (`exo.cache.portal.SiteKeyById.TimeToLive`) | local (`exo.cache.portal.SiteKeyById.cacheMode`) |
| `commons.subject` |  | 300 (`exo.cache.commons.subject.MaxNodes`) | 600 (`exo.cache.commons.subject.TimeToLive`) | local (`exo.cache.commons.subject.cacheMode`) |
| `social.richEditorConfiguration` |  | 300 (`exo.cache.social.richEditorConfiguration.MaxNodes`) | 600 (`exo.cache.social.richEditorConfiguration.TimeToLive`) | local (`exo.cache.social.richEditorConfiguration.cacheMode`) |
| `ecms.BaseResourceCache` |  | 300 (`exo.cache.ecms.BaseResourceCache.MaxNodes`) | 600 (`exo.cache.ecms.BaseResourceCache.TimeToLive`) | local (`exo.cache.ecms.BaseResourceCache.cacheMode`) |
| `chat.UserAccessToken` |  | 300 (`exo.cache.chat.UserAccessToken.MaxNodes`) | 600 (`exo.cache.chat.UserAccessToken.TimeToLive`) | local (`exo.cache.chat.UserAccessToken.cacheMode`) |
| `chat.UserMatrixId` |  | 300 (`exo.cache.chat.UserMatrixId.MaxNodes`) | 600 (`exo.cache.chat.UserMatrixId.TimeToLive`) | local (`exo.cache.chat.UserMatrixId.cacheMode`) |
| `wallet.blockchain.tokenBalance` |  | 300 (`exo.cache.wallet.blockchain.tokenBalance.MaxNodes`) | 5 (`exo.cache.wallet.blockchain.tokenBalance.TimeToLive`) | local (`exo.cache.wallet.blockchain.tokenBalance.cacheMode`) |
| `wallet.blockchain.etherBalance` |  | 300 (`exo.cache.wallet.blockchain.etherBalance.MaxNodes`) | 5 (`exo.cache.wallet.blockchain.etherBalance.TimeToLive`) | local (`exo.cache.wallet.blockchain.etherBalance.cacheMode`) |
| `wallet.blockchain.transaction` |  | 300 (`exo.cache.wallet.blockchain.transaction.MaxNodes`) | 600 (`exo.cache.wallet.blockchain.transaction.TimeToLive`) | local (`exo.cache.wallet.blockchain.transaction.cacheMode`) |
| `wallet.blockchain.transactionReceipt` |  | 300 (`exo.cache.wallet.blockchain.transactionReceipt.MaxNodes`) | 600 (`exo.cache.wallet.blockchain.transactionReceipt.TimeToLive`) | local (`exo.cache.wallet.blockchain.transactionReceipt.cacheMode`) |
| `portal.coediting` |  | 300 (`exo.cache.portal.coediting.MaxNodes`) | 20 (`meeds.coediting.lockPeriod`) | replication (`exo.cache.portal.coediting.cacheMode`) |
<!-- caches:end -->
