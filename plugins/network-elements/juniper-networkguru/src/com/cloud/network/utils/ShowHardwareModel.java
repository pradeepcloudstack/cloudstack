package com.cloud.network.utils;

import java.util.HashMap;
import java.util.Map;

import net.juniper.netconf.Device;
import net.juniper.netconf.CommitException;
import net.juniper.netconf.LoadException;
import net.juniper.netconf.NetconfException;
import net.juniper.netconf.XML;
import com.cloud.utils.exception.CloudRuntimeException;

public class ShowHardwareModel {
	
	
	public static String getHardwareModel(String deviceNameOrIp,String userName,String password)
	{
		String rpc = "get-system-information";
		Device device = null;
		try
		{
			device = new Device(deviceNameOrIp, userName, password, null); 
			device.setPort(22);
			device.connect();
			XML reply = device.executeRPC(rpc);
			String xml[] = reply.toString().split("<hardware-model>");
			String hardwareModelXml[] = xml[1].split("</hardware-model>");
			return  hardwareModelXml[0];
		}
		catch (Exception e) {         
			throw new CloudRuntimeException("Orchestration through Netconf failed"+e.getMessage());
		}
		finally
		{
			device.close();
		}

	}

}
