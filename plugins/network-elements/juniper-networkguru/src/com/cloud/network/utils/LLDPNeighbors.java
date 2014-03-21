package com.cloud.network.utils;

import java.util.HashMap;
import java.util.Map;

import net.juniper.netconf.Device;
import net.juniper.netconf.CommitException;
import net.juniper.netconf.LoadException;
import net.juniper.netconf.NetconfException;
import com.cloud.utils.exception.CloudRuntimeException;


public class LLDPNeighbors {
	
	public static Map<String,String> getNeighborsInterfaceSystemMap(String deviceNameOrIp,String userName,String password)
	{
		String cli = "show lldp neighbors";
		Device device = null;
		try
		{
			device = new Device(deviceNameOrIp, userName, password, null); 
			Map<String,String> interfaceSystemMap = new HashMap<String,String>();
			device.connect();

			String cli_reply = device.runCliCommand(cli);     
			String cliReply[] = cli_reply.split("\n");
			for(int i=1;i<cliReply.length;i++)
			{
				cliReply[i]= cliReply[i].replaceAll("\\s+", " ");
				String [] neighbourInfo = cliReply[i].split(" ");
				interfaceSystemMap.put(neighbourInfo[4].replaceAll(".englab.juniper.net",""),neighbourInfo[0].replace(".0",""));
			}
			return interfaceSystemMap;
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
