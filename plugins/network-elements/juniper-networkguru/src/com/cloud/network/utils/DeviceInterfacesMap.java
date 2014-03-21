package com.cloud.network.utils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DeviceInterfacesMap {

	public static Map<String,Set<String>>  getDeviceInterfaceMap(String deviceInterfaceInfo)
	{
		String []ports = null;
		ports = deviceInterfaceInfo.split(",");
		Map<String,Set<String>> devicePortMap = new HashMap<String, Set<String>>();
		for(String port: ports)
		{
			String deviceInfo[] = port.split(":",2);
			if(devicePortMap.containsKey(deviceInfo[0]))
			{
				Set<String> interfaces = devicePortMap.get(deviceInfo[0]);
				interfaces.add(deviceInfo[1]);
			}
			else
			{
				Set<String> interfaces = new HashSet<String>();
				interfaces.add(deviceInfo[1]);
				devicePortMap.put(deviceInfo[0],interfaces) ;
			}

		}

		return devicePortMap;
	}

}
