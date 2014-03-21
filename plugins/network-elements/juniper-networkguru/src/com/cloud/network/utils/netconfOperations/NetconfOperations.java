package com.cloud.network.utils.netconfOperations;

import net.juniper.netconf.Device;
import net.juniper.netconf.XML;

/**
 * User: hkp
 * Date: Oct 28, 2013
 * Time: 4:23:38 PM
 */
public class NetconfOperations {
    public static enum EDIT_CONFIG_MODE {
        merge,
        replace,
        none
    }

    public static void commit(Device device, String xmlConfig) throws Exception {
        boolean isLocked = false;
        try {
            device.setPort(22);
            device.connect();

            //Lock the configuration first
            try {
                isLocked = device.lockConfig();
                if (!isLocked) {
                    throw new Exception("Couldnot lock the configuration");
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new Exception("Couldnot lock the configuration - " + e.getMessage());
            }

            try {
                  device.loadXMLConfiguration(xmlConfig,EDIT_CONFIG_MODE.merge.toString());
            } catch (Exception e) {
                e.printStackTrace();
                throw new Exception("Failed to load the configuration - " + e.getMessage());
            }

            try {
                device.commit();
            } catch (Exception e) {
                e.printStackTrace();
                throw new Exception("Failed to commit the configuration - " + e.getMessage());
            }
        } finally {
            if (isLocked) {
                device.unlockConfig();
            }
            device.close();
        }
    }

    public static XML executeRPC(Device device, String rpcContents) throws Exception {
        device.setPort(22);
        device.connect();

        return device.executeRPC(rpcContents);
    }

    public static String runCliCommand(Device device, String cliCmd) throws Exception {
        device.setPort(22);
        device.connect();
        return device.runCliCommand(cliCmd);
    }
}
