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
package org.exoplatform.services.connector.credentials;

import java.util.Set;

/**
 * A way to obtain, for one user on one connector, ready-to-use authentication
 * material against a remote server (Personal, a shared technical account, an
 * impersonation mechanism such as BlueMind's "sudo", a future OAuth/JWT provider...).
 * <p>
 * Providers form a flat, open list: there is no type/category grouping instances,
 * each implementation is its own independent recipe. A connector never inspects how a
 * provider does its job - it only calls {@link #produce(ConnectorCredentialsContext)} and
 * uses the {@link ConnectorCredentials} it gets back.
 * <p>
 * Implementations are discovered as Spring beans of this type (no dedicated
 * registration annotation needed) by the resolution service that looks them up by
 * {@link #getName()} - see the connector-level provider selection field on the
 * admin connector entity.
 */
public interface ConnectorCredentialsProvider {

   /**
    * Stable identifier stored on the connector's admin configuration (e.g.
    * {@code "personal"}, {@code "bluemind-sudo"}) to select this provider. Must be
    * unique platform-wide.
    */
   String getName();

   /**
    * The channels this provider can produce material for. A provider does not have
    * to support every channel - e.g. a provider only ever used for CalDAV would only
    * declare {@link ConnectorCredentialsChannel#HTTP}.
    */
   Set<ConnectorCredentialsChannel> getSupportedChannels();

   /**
    * Whether this provider requires an action from the target user before it can
    * produce material for them (e.g. Personal requires the user to have entered
    * their own credentials once). Only a provider answering {@code false} here is
    * eligible to be configured as a "default connector" for a population.
    */
   boolean requiresUserAction();

   /**
    * Produces ready-to-use material for the given context, typed for one of
    * {@link #getSupportedChannels()}. Implementations are expected to cache
    * expensive production (e.g. a remote "sudo" call) themselves, single-flight and
    * TTL-bound, rather than push that concern onto callers.
    *
    * @throws ConnectorCredentialsException if material cannot be produced (remote call
    *            failure, technical account misconfigured, target user unknown to the
    *            remote server...)
    */
   ConnectorCredentials produce(ConnectorCredentialsContext context) throws ConnectorCredentialsException;

   /**
    * Invalidates any material this provider may have cached for the given context,
    * so the next {@link #produce(ConnectorCredentialsContext)} call is forced to produce
    * fresh material. Called by the caller exactly once after material has proven
    * wrong (a 401/AUTHENTICATIONFAILED) - never as part of a retry loop.
    */
   void invalidate(ConnectorCredentialsContext context);

   /**
    * Which identity the material this provider would produce is going to address,
    * when that differs from the one it authenticates as. Answers {@code null} when
    * there is nothing to add, and the connector then addresses the resource exactly
    * as it already does.
    * <p>
    * Deliberately answerable <b>without</b> producing anything: for an impersonation
    * provider the target is an <i>input</i> to production, not a property of its
    * result - BlueMind's {@code su(String login)} takes it as a parameter. A connector
    * that only needs it to build a URL or a From header therefore pays no remote call,
    * and "this user cannot be named on the remote server" surfaces as its own failure
    * rather than being folded into "the remote server refused".
    * <p>
    * <b>Implementations must derive it in one place and have
    * {@link #produce(ConnectorCredentialsContext)} use that same code.</b> Two
    * independent derivations let the request the provider makes and the resource the
    * connector addresses drift apart - which is not an authentication error but a
    * valid session pointed at someone else's data, with nothing raised.
    */
   default String resolveTargetIdentity(ConnectorCredentialsContext context) {
      return null;
   }

}
