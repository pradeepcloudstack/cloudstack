package com.cloud.network.utils;

import com.cloud.network.utils.Utils;

/**
 * User: hkp
 * Date: Dec 12, 2013
 * Time: 1:25:34 PM
 */
public class JunosL2XMLConfigs {

    private static JunosL2XMLConfigs instance = new JunosL2XMLConfigs();

    public XMLConfigSyntax CREATE_VLAN = new XMLConfigSyntax("<vlans><vlan><name>%1</name><vlan-id>%2</vlan-id></vlan></vlans>");
    public XMLConfigSyntax DELETE_VLAN = new XMLConfigSyntax("<vlans><vlan delete=\"delete\"><name>%1</name></vlan></vlans>");
    public XMLConfigSyntax CREATE_TRUNK_PORT_WITH_VLAN_MEMBER = new XMLConfigSyntax("<interfaces><interface><name>%1</name><unit><name>%2</name><family><ethernet-switching><port-mode>trunk</port-mode><vlan><members>%3</members></vlan></ethernet-switching></family></unit></interface></interfaces>");
    public XMLConfigSyntax DELETE_VLAN_MEMBER = new XMLConfigSyntax("<interfaces><interface><name>%1</name><unit><name>%2</name><family><ethernet-switching><vlan><members delete=\"delete\">%3</members></vlan></ethernet-switching></family></unit></interface></interfaces>");

    protected JunosL2XMLConfigs() {

    }

    public static JunosL2XMLConfigs getInstance() {
        return instance;
    }

    public static class XMLConfigSyntax {
        private String cfg;

        public XMLConfigSyntax(String config) {
            cfg = config;
        }

        public String substitute(String... arguments) {
            return Utils.patternReplace(cfg, arguments);
        }
    }
}
