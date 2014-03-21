package com.cloud.network.utils;

/**
 * User: hkp
 * Date: Dec 12, 2013
 * Time: 1:31:58 PM
 */
public class ELS_JunosL2XMLConfigs extends JunosL2XMLConfigs {
    private static ELS_JunosL2XMLConfigs instance = new ELS_JunosL2XMLConfigs();

    public XMLConfigSyntax CREATE_TRUNK_PORT_WITH_VLAN_MEMBER = new XMLConfigSyntax("<interfaces><interface><name>%1</name><unit><name>%2</name><family><ethernet-switching><interface-mode>trunk</interface-mode><vlan><members>%3</members></vlan></ethernet-switching></family></unit></interface></interfaces>");

    private ELS_JunosL2XMLConfigs() {

    }

    public static ELS_JunosL2XMLConfigs getInstance() {
        return instance;
    }
}
