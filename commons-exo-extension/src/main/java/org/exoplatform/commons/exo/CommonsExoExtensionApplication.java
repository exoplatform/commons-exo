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
package org.exoplatform.commons.exo;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.PropertySource;

import io.meeds.spring.AvailableIntegration;
import io.meeds.spring.kernel.PortalApplicationContextInitializer;

/**
 * Spring Boot bootstrap of commons-exo-extension - a pre-existing, always-deployed
 * webapp (legacy kernel components: cache configuration for core services such as
 * SettingService) that had no Spring context of its own until now. This is where
 * commons-exo's own shared beans (e.g. {@code ConnectorCredentialsConfiguration} in
 * {@code exo.core.component.connector.credentials}) get their Spring wiring: this
 * module is core platform infrastructure, never an optional add-on, so it is the
 * right place to own that wiring once instead of every connector add-on redeclaring
 * it, or - worse - a self-contained library jar auto-configuring on every Spring
 * Boot webapp in the platform regardless of relevance.
 * <p>
 * Excludes Spring Boot's own JPA/Liquibase auto-configurations: with no entity and no
 * changelog in this module, they trigger anyway - the shared platform classpath makes
 * Liquibase/Hibernate visible here too - and fail (Liquibase looks for a changelog
 * that does not exist).
 */
@SpringBootApplication(scanBasePackages = { "org.exoplatform.services.connector.credentials",
    AvailableIntegration.KERNEL_MODULE, AvailableIntegration.WEB_MODULE },
    exclude = { LiquibaseAutoConfiguration.class, HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class })
@PropertySource("classpath:application.properties")
@PropertySource("classpath:application-common.properties")
public class CommonsExoExtensionApplication extends PortalApplicationContextInitializer {

}
