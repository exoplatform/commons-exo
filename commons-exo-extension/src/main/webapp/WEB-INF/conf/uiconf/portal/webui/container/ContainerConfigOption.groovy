/**
 * Copyright (C) 2023 eXo Platform SAS.
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

import org.exoplatform.webui.core.model.SelectItemCategory;
import org.exoplatform.webui.core.model.SelectItemOption;

  List templates = new ArrayList();

  SelectItemCategory table = new SelectItemCategory("table");
  table.addSelectItemOption(new SelectItemOption("simpleTable",
        "<container template=\"system:/groovy/portal/webui/container/UISimpleTableContainer.gtmpl\"></container>",
        "SimpleTableContainerLayout"));
  table.addSelectItemOption(new SelectItemOption("siteTopBar",
    "<container id=\"UITopBarContainer\" template=\"system:/groovy/portal/webui/container/UITopBarContainer.gtmpl\">\n" +
    "  <access-permissions>Everyone</access-permissions>\n" +
    "  <container id=\"left-topBar-container\" template=\"system:/groovy/portal/webui/container/UIContainer.gtmpl\">\n" +
    "    <access-permissions>Everyone</access-permissions>\n" +
    "    <portlet-application>\n" +
    "      <portlet>\n" +
    "        <application-ref>social-portlet</application-ref>\n" +
    "        <portlet-ref>TopBarLogo</portlet-ref>\n" +
    "      </portlet>\n" +
    "      <title>Company Logo</title>\n" +
    "      <access-permissions>Everyone</access-permissions>\n" +
    "      <show-info-bar>false</show-info-bar>\n" +
    "      <show-application-state>false</show-application-state>\n" +
    "    </portlet-application>\n" +
    "    <portlet-application>\n" +
    "      <portlet>\n" +
    "        <application-ref>layout-management</application-ref>\n" +
    "        <portlet-ref>SiteNavigation</portlet-ref>\n" +
    "      </portlet>\n" +
    "      <title>site navigation</title>\n" +
    "      <access-permissions>Everyone</access-permissions>\n" +
    "      <show-info-bar>false</show-info-bar>\n" +
    "      <show-application-state>false</show-application-state>\n" +
    "    </portlet-application>\n" +
    "  </container>\n" +
    "  <container id=\"middle-topBar-container\" template=\"system:/groovy/portal/webui/container/UIContainer.gtmpl\">\n" +
    "    <portlet-application>\n" +
    "      <portlet>\n" +
    "        <application-ref>social-portlet</application-ref>\n" +
    "        <portlet-ref>TopBarMenu</portlet-ref>\n" +
    "      </portlet>\n" +
    "      <title>Top Bar Menu </title>\n" +
    "      <access-permissions>Everyone</access-permissions>\n" +
    "      <show-info-bar>false</show-info-bar>\n" +
    "      <show-application-state>false</show-application-state>\n" +
    "    </portlet-application>\n" +
    "  </container>\n" +
    "  <container id=\"right-topBar-container\" template=\"system:/groovy/portal/webui/container/UIContainer.gtmpl\">\n" +
    "  </container>\n" +
    "</container>\n",
    "SiteTopBarContainerLayout"));
  templates.add(table);

  SelectItemCategory row = new SelectItemCategory("row");
  row.addSelectItemOption(new SelectItemOption("simpleRow",
    "<container template=\"system:/groovy/portal/webui/container/UISimpleRowContainer.gtmpl\"></container>",
    "SimpleRowContainerLayout"));
  templates.add(row);

  SelectItemCategory column = new SelectItemCategory("column");
  column.addSelectItemOption(new SelectItemOption("simpleColumn",
    "<container template=\"system:/groovy/portal/webui/container/UIResponsiveColumnGroupContainer.gtmpl\">" +
    "  <container template=\"system:/groovy/portal/webui/container/UISimpleColumnContainer.gtmpl\">" +
    "  <factory-id>SimpleColumnContainer</factory-id>" +
    "  </container>" +
    "</container>",
    "SimpleColumnContainerLayout"));
  templates.add(column);

  return templates;
