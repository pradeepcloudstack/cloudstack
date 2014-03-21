package com.cloud.network.guru;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.dao.HostDao;
import com.cloud.host.HostVO;
import com.cloud.network.JuniperNDAPINaasServiceNetworkMapVO;
import com.cloud.network.Network;
import com.cloud.network.NetworkManager;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.Network.State;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.Mode;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.PhysicalNetworkTrafficType;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.naas.dao.JuniperNDAPINaasServiceNetworkMapDao;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.utils.DeviceInterfacesMap;
import com.cloud.network.utils.JuniperNDAPIConstants;
import com.cloud.network.utils.JunosL2XMLConfigs;
import com.cloud.network.utils.ELS_JunosL2XMLConfigs;
import com.cloud.network.utils.LLDPNeighbors;
import com.cloud.network.utils.ShowHardwareModel;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.UserContext;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

import javax.ejb.Local;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import org.springframework.stereotype.Component;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.cloud.network.utils.netconfOperations.NetconfOperations;

import javax.xml.parsers.ParserConfigurationException;

import net.juniper.netconf.Device;
import net.juniper.netconf.CommitException;
import net.juniper.netconf.LoadException;
import net.juniper.netconf.NetconfException;



//@Component
@Local(value=NetworkGuru.class)
public class JuniperNetworkGuru extends GuestNetworkGuru{
	private static final Logger s_logger = Logger.getLogger(JuniperNetworkGuru.class);

	@Inject
	AccountDao _accountDao;
	@Inject
	NetworkManager _networkMgr;
	@Inject
	NetworkDao _networkDao;
	@Inject
	DataCenterDao _zoneDao;
	@Inject
	PortForwardingRulesDao _pfRulesDao;
	@Inject 
	PhysicalNetworkTrafficTypeDao _physNetTTDao;
	@Inject
	JuniperNDAPINaasServiceNetworkMapDao _juniperNDAPIDao;
	@Inject
	HostDao _hostDao;

	private static Properties configProp = new Properties();


	public JuniperNetworkGuru(){
		super();
		_isolationMethods = new PhysicalNetwork.IsolationMethod[] { PhysicalNetwork.IsolationMethod.VLAN};
	}


	/**
	 * Initialized the configuration.
	 */

	public void loadProerties() {		
		String path = "/etc/cloudstack/management/JuniperNetworkGuru.properties";
		try {
			FileInputStream file = new FileInputStream(path);
			configProp.load(file);
		} catch (FileNotFoundException e) {
			s_logger.error("NDAPI.properties not found");
		} catch (IOException e) {
			s_logger.error("Error Loading default NDAPI Configuration.");
		}
		
	}

	@Override
	protected boolean canHandle(NetworkOffering offering,
			NetworkType networkType, PhysicalNetwork physicalNetwork) {
		// This guru handles only Guest Isolated network that supports Source nat service
		if (networkType == NetworkType.Advanced
				&& isMyTrafficType(offering.getTrafficType())
				&& (offering.getGuestType() == Network.GuestType.Isolated)
				&& isMyIsolationMethod(physicalNetwork)) {
			return true;
		} else {
			s_logger.trace("We only take care of Guest networks of type   " + Network.GuestType.Isolated + " in zone of type " + NetworkType.Advanced + " using isolation method VLAN.");
			return false;
		}

	}

	protected boolean canHandle(NetworkOffering offering, DataCenter dc) {
		// this guru handles only Guest networks in Advance zone with source nat service disabled
		if (dc.getNetworkType() == NetworkType.Advanced && isMyTrafficType(offering.getTrafficType()) && offering.getGuestType() == GuestType.Shared) {
			return true;
		} else {
			s_logger.trace("We only take care of Guest networks of type " + GuestType.Shared);
			return false;
		}
	}

	@Override
	public Network design(NetworkOffering offering, DeploymentPlan plan,
			Network userSpecified, Account owner) {
		s_logger.debug("design called");

		//Loads the NDAPI property file.
		loadProerties();
		
		String pluginStatus = configProp.getProperty(JuniperNDAPIConstants.PLUGINSTATUS);
		if(pluginStatus.equals("Disable"))
		{
			return null;
		}
		DataCenter dc = _dcDao.findById(plan.getDataCenterId());
		PhysicalNetworkVO physnet = _physicalNetworkDao.findById(plan.getPhysicalNetworkId());

		State state = State.Allocated;
		if (dc.getNetworkType() == NetworkType.Basic) {
			state = State.Setup;
		}


		if (Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()))) {
			return null;
		}

		NetworkVO config = null ;
		
		if(offering.getGuestType() == Network.GuestType.Isolated)
		{
			config = (NetworkVO) super.design(offering, plan, userSpecified, owner);
		}

        
		else if(offering.getGuestType() == Network.GuestType.Shared)
		{ 	 

			config = new NetworkVO(offering.getTrafficType(), Mode.Dhcp, BroadcastDomainType.Vlan, offering.getId(), state, plan.getDataCenterId(), plan.getPhysicalNetworkId());

			if (userSpecified != null) {
				if ((userSpecified.getCidr() == null && userSpecified.getGateway() != null) || (userSpecified.getCidr() != null && userSpecified.getGateway() == null)) {
					throw new InvalidParameterValueException("cidr and gateway must be specified together.");
				}

				if ((userSpecified.getIp6Cidr() == null && userSpecified.getIp6Gateway() != null) || (userSpecified.getIp6Cidr() != null && userSpecified.getIp6Gateway() == null)) {
					throw new InvalidParameterValueException("cidrv6 and gatewayv6 must be specified together.");
				}

				if (userSpecified.getCidr() != null) {
					config.setCidr(userSpecified.getCidr());
					config.setGateway(userSpecified.getGateway());
				}

				if (userSpecified.getIp6Cidr() != null) {
					config.setIp6Cidr(userSpecified.getIp6Cidr());
					config.setIp6Gateway(userSpecified.getIp6Gateway());
				}

				if (userSpecified.getBroadcastUri() != null) {
					config.setBroadcastUri(userSpecified.getBroadcastUri());
					//config.setState(State.Setup);
				}

				if (userSpecified.getBroadcastDomainType() != null) {
					config.setBroadcastDomainType(userSpecified.getBroadcastDomainType());
				}
			}
		}


		if (config == null) {
			return null;
		} else if (_networkModel.networkIsConfiguredForExternalNetworking(plan.getDataCenterId(), config.getId())) {
			/* In order to revert userSpecified network setup */
			config.setState(State.Allocated);
		}



		s_logger.debug("Physical Network Id  :" + plan.getPhysicalNetworkId());
		s_logger.debug("Physical Network Name  :" + physnet.getName()); 
		s_logger.debug("Zone name :" + dc.getName());		
		s_logger.debug("GateWay : "+config.getGateway()); 
		s_logger.debug("GuestType :"+offering.getGuestType());
		s_logger.debug("Traffic Type :" + offering.getTrafficType().toString());
		s_logger.debug("Network Type :" + dc.getNetworkType());
		s_logger.debug("Braodcast Domain Type :" + config.getBroadcastDomainType());

		return config;
	}



	//Creates Naas Service
	public String createNaasService(String commanURL,String tenantId,String naasDomainId,String vlanValue) throws Exception
	{
		String line, jsonString = "";		
		String naasUrl = commanURL+"tenants/"+tenantId+"/naas-services";
		URL url = new URL(naasUrl);

		JSONObject naasObject = new JSONObject();
		naasObject.put(JuniperNDAPIConstants.ELEMENTNAME,vlanValue+"NaasService");
		JSONObject naasDomainObject = new JSONObject();
		naasDomainObject.put(JuniperNDAPIConstants.HREF,"/api/juniper/nd/naas-domains/"+naasDomainId);
		naasObject.put(JuniperNDAPIConstants.NAASDOMAIN,naasDomainObject);
		JSONObject naasServiceObject = new JSONObject();
		naasServiceObject.put(JuniperNDAPIConstants.NAASSERVICE,naasObject);

		String naasPayload = naasServiceObject.toString();

		HttpURLConnection urlconn;

		urlconn = getURLConnection(url,JuniperNDAPIConstants.POST,JuniperNDAPIConstants.ACCEPT_NAASSERVICE, JuniperNDAPIConstants.CONTENT_TYPE_NAASSERVICE);
		OutputStream os = urlconn.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
		writer.write(naasPayload);
		writer.close();
		os.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();
		return jsonString;
	}

	//Creates connectivity group
	public String createConnectivityGroup(String commanURL,String tenantId,String naasServiceId,String vlanValue) throws Exception
	{
		String line,jsonString = "";

		String cgId = null;
		String urlCG = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId+"/connectivity-groups";
		URL url = new URL(urlCG);

		JSONObject cgObject = new JSONObject();
		cgObject.put(JuniperNDAPIConstants.ELEMENTNAME, vlanValue+"CGroup");
		JSONObject cgMainObject = new JSONObject();
		cgMainObject.put(JuniperNDAPIConstants.CONNECTIVITY_GROUP,cgObject );
		String cgPayload = cgMainObject.toString();


		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.POST,JuniperNDAPIConstants.ACCEPT_CONNECTIVITY_GROUP, JuniperNDAPIConstants.CONTENT_TYPE_CONNECTIVITY_GROUP);
		OutputStream os = urlconn.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
		writer.write(cgPayload);
		writer.close();
		os.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();
		return jsonString;
	}

	//Creates port
	public String createPort(String commanURL,String tenantId,String naasServiceId,String cgId,String portName) throws Exception
	{
		String line,jsonString = "";
		String urlport = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId+"/connectivity-groups/"+cgId+"/ports";
		URL url = new URL(urlport);


		JSONObject portObject = new JSONObject();
		portObject.put(JuniperNDAPIConstants.ELEMENTNAME,portName);
		JSONObject portMainObject = new JSONObject();
		portMainObject.put(JuniperNDAPIConstants.PORT,portObject );
		String portPayload = portMainObject.toString();

		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.POST,JuniperNDAPIConstants.ACCEPT_PORT, JuniperNDAPIConstants.CONTENT_TYPE_PORT);
		OutputStream os = urlconn.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
		writer.write(portPayload);
		writer.close();
		os.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();
		return jsonString;
	}


	//Creates L2 connectivity service
	public String createL2CS(String commanURL,String tenantId,String naasServiceId,String vlanValue,String cgId,String prefix) throws Exception
	{
		String line,jsonString = "";
		String l2csURL = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId+"/l2-connectivity-services";


		JSONObject cgbject = new JSONObject();
		cgbject.put(JuniperNDAPIConstants.HREF,"/api/juniper/nd/connectivity-groups/"+cgId);
		JSONObject l2csObject = new JSONObject();		
		l2csObject.put(JuniperNDAPIConstants.ELEMENTNAME,vlanValue+"L2CS");
		l2csObject.put(JuniperNDAPIConstants.PREFIX, prefix);
		l2csObject.put(JuniperNDAPIConstants.VLANID,vlanValue);
		l2csObject.put(JuniperNDAPIConstants.CONNECTIVITY_GROUP, cgbject);
		JSONObject l2csMainObject = new JSONObject();
		l2csMainObject.put(JuniperNDAPIConstants.L2CS,l2csObject );
		String l2CSPayload = l2csMainObject.toString();


		URL url = new URL(l2csURL);

		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.POST,JuniperNDAPIConstants.ACCEPT_L2CS,JuniperNDAPIConstants.CONTENT_TYPE_L2CS);
		OutputStream os = urlconn.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
		writer.write(l2CSPayload);
		writer.close();
		os.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();
		return jsonString;
	}

	//Activates naas-service
	public String activateService(String commanURL,String tenantId,String naasServiceId,String naasDomainId,String vlanValue) throws Exception
	{
		String line,jsonString = "";
		String naasServicename = "\""+vlanValue+"NaasService\"";
		String urlString = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId;
		URL url = new URL(urlString);

		JSONObject naasObject = new JSONObject();
		naasObject.put(JuniperNDAPIConstants.ELEMENTNAME,vlanValue+"NaasService");
		naasObject.put(JuniperNDAPIConstants.REQUESTEDACTION,JuniperNDAPIConstants.ACTIVATE);
		JSONObject naasDomainObject = new JSONObject();
		naasDomainObject.put(JuniperNDAPIConstants.HREF,"/api/juniper/nd/naas-domains/"+naasDomainId);
		naasObject.put(JuniperNDAPIConstants.NAASDOMAIN,naasDomainObject);
		JSONObject naasServiceObject = new JSONObject();
		naasServiceObject.put(JuniperNDAPIConstants.NAASSERVICE,naasObject);
		String payload = naasServiceObject.toString();

		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.PUT,JuniperNDAPIConstants.ACCEPT_NAASSERVICE ,JuniperNDAPIConstants.CONTENT_TYPE_NAASSERVICE);
		OutputStream os = urlconn.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
		writer.write(payload);
		writer.close();
		os.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();
		return jsonString;
	}
	
	//Deactivates naas-service
	public String deActivateService(String commanURL,String tenantId,String naasServiceId,String naasDomainId,String vlanValue) throws Exception
	{
		String line,jsonString = "";
		String urlString = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId;
		URL url = new URL(urlString);

		JSONObject naasObject = new JSONObject();
		naasObject.put(JuniperNDAPIConstants.ELEMENTNAME,vlanValue+"NaasService");
		naasObject.put(JuniperNDAPIConstants.REQUESTEDACTION,JuniperNDAPIConstants.DEACTIVATE);
		JSONObject naasDomainObject = new JSONObject();
		naasDomainObject.put(JuniperNDAPIConstants.HREF,"/api/juniper/nd/naas-domains/"+naasDomainId);
		naasObject.put(JuniperNDAPIConstants.NAASDOMAIN,naasDomainObject);
		JSONObject naasServiceObject = new JSONObject();
		naasServiceObject.put(JuniperNDAPIConstants.NAASSERVICE,naasObject);
		String payload = naasServiceObject.toString();

		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.PUT,JuniperNDAPIConstants.ACCEPT_NAASSERVICE ,JuniperNDAPIConstants.CONTENT_TYPE_NAASSERVICE);
		OutputStream os = urlconn.getOutputStream();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
		writer.write(payload);
		writer.close();
		os.close();
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();
		return jsonString;
	}

	//This method will delete NaasService
	public void deleteNaasService(String commanURL, String tenantId, String naasServiceId) throws Exception
	{
		String urlString = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId;
		URL url = new URL(urlString);
		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.DELETE,JuniperNDAPIConstants.ACCEPT_NAASSERVICE ,JuniperNDAPIConstants.CONTENT_TYPE_NAASSERVICE);
		urlconn.connect();
		int responseCode = urlconn.getResponseCode();
		urlconn.disconnect();	
	}
	
	//This method will get naas service for given id
	public String getNaasService(String commanURL, String tenantId, String naasServiceId) throws Exception
	{
		String line,jsonString = "";
		String urlString = commanURL+"tenants/"+tenantId+"/naas-services/"+naasServiceId;
		URL url = new URL(urlString);
		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.GET,JuniperNDAPIConstants.ACCEPT_NAASSERVICE ,JuniperNDAPIConstants.CONTENT_TYPE_NAASSERVICE);
		urlconn.connect();
		int responseCode = urlconn.getResponseCode();
		
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();	
		
		return jsonString;
	}
	
	//This method will get naas-domainain for given id
	public String getNaasDomain(String commanURL,String naasDomainId) throws Exception
	{
		String line,jsonString = "";
		String urlString = commanURL+"naas-domains/"+naasDomainId;
		URL url = new URL(urlString);
		HttpURLConnection urlconn = getURLConnection(url,JuniperNDAPIConstants.GET,JuniperNDAPIConstants.ACCEPT_NAASSDOMAIN ,JuniperNDAPIConstants.CONTENT_TYPE_NAASDOMAIN);
		urlconn.connect();
		int responseCode = urlconn.getResponseCode();
		
		BufferedReader br = new BufferedReader(new InputStreamReader(urlconn.getInputStream()));
		while ((line = br.readLine()) != null) {
			jsonString += line;
		}
		br.close();
		urlconn.disconnect();	
		
		return jsonString;
	}
	
	//This method will returns array of Revenue ports for given naas domain.
	private String[] getRevenuePortsForNaasDomain(String naasDomainResponse)
	{
		try
		{
			JSONObject jObject = new JSONObject(naasDomainResponse);
			JSONObject naasDomain = jObject.getJSONObject(JuniperNDAPIConstants.NAASDOMAIN);
			JSONObject revenuePorts = naasDomain.getJSONObject(JuniperNDAPIConstants.REVENUEPORTS);
			String listOfPorts = revenuePorts.getJSONArray(JuniperNDAPIConstants.REVENUEPORT).toString();
			listOfPorts = listOfPorts.replace("\"","");
			listOfPorts = listOfPorts.replace("[","");
			listOfPorts = listOfPorts.replace("]","");						
			String []ports = listOfPorts.split(",");
			
			return ports;
		}
		catch(Exception e)
		{
			s_logger.error("Failed to get revenue ports for Naas Domain."+e.getLocalizedMessage());
			throw new CloudRuntimeException("Creation of L2 Service failed"+e.getMessage());
		}
	}

	//This method will process o/p response of REST request and returns uuid of the service
	private String getUuid(String restResponse,String objectName)
	{
		String uuid = null;
		try
		{
			JSONObject jObject = new JSONObject(restResponse);
			JSONObject geoObject = jObject.getJSONObject(objectName);
			uuid = geoObject.getString("uuid");
		}
		catch(Exception e)
		{
			s_logger.error("Failed to fetch uuid"+e.getLocalizedMessage());
			throw new CloudRuntimeException("Creation of L2 Service failed"+e.getMessage());
		}
		return uuid;
	}
	
	//This method will process o/p response of REST request and returns current state of the naas service
	public String getCurrentState(String restResponse)
	{
		String currentState = null;
		try
		{
			JSONObject jObject = new JSONObject(restResponse);
			JSONObject geoObject = jObject.getJSONObject(JuniperNDAPIConstants.NAASSERVICE);
			currentState = geoObject.getString("current-state");
		}
		catch(Exception e)
		{
			s_logger.error("Failed to fetch current state of NaasService"+e.getLocalizedMessage());
			throw new CloudRuntimeException("Creation of L2 Service failed"+e.getMessage());
		}
		return currentState;
	}


	//This method will return URLConnection.
	public HttpURLConnection getURLConnection(URL url,String operation,String acceptHeader,String contentHeader) throws IOException,ProtocolException
	{
		HttpURLConnection urlconn = (HttpURLConnection) url.openConnection();
		urlconn.setDoInput(true);
		urlconn.setDoOutput(true);
		urlconn.setRequestProperty (JuniperNDAPIConstants.AUTHORIZATION,JuniperNDAPIConstants.BASIC + getEncodedAuthorization(configProp.getProperty(JuniperNDAPIConstants.USERNAME),configProp.getProperty(JuniperNDAPIConstants.PASSWORD)));
		urlconn.setRequestMethod(operation);
		urlconn.setRequestProperty(JuniperNDAPIConstants.ACCEPT,acceptHeader);
		urlconn.setRequestProperty(JuniperNDAPIConstants.ContentType,contentHeader);
		return urlconn;
	}


	//This method will return encoded login password for given username and password
	public String getEncodedAuthorization(String username, String password)
	{
		String loginPassword = username+ ":" +password;
		return new sun.misc.BASE64Encoder().encode (loginPassword.getBytes());
	}

	//This method will parse urlString and returns vlan id
    public String getVlanId(String urlString)
    {
		String [] uriSpilt = urlString.split("/");
		String vlanValue = uriSpilt[2];
		return vlanValue;
    }

	@Override
	public Network implement(Network config, NetworkOffering offering,
			DeployDestination dest, ReservationContext context)
					throws InsufficientVirtualNetworkCapcityException {
		assert (config.getState() == Network.State.Implementing) : "Why are we implementing " + config;
		s_logger.debug("implement called network: " + config.toString());

		//Loads the NDAPI property file.
		loadProerties();

		Network networkImplemented = null;

		if (Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()))) {
			return null;
		}

		if (!_networkModel.networkIsConfiguredForExternalNetworking(config.getDataCenterId(), config.getId())) {
			
			if(offering.getGuestType() == Network.GuestType.Isolated)
			{
			 networkImplemented =  super.implement(config, offering, dest, context);
			}
			else if(offering.getGuestType() == Network.GuestType.Shared)
			{
			        networkImplemented = config;
			}

			s_logger.debug("Guest network id  :" + networkImplemented.getUuid());
			s_logger.debug("Network offering id  :" + networkImplemented.getNetworkOfferingId());
			s_logger.debug("Pod id: " + dest.getPod().getUuid());
			s_logger.debug("Pod Name: " + dest.getPod().getName());
			s_logger.debug("Cluster id: " + dest.getCluster().getUuid());
			s_logger.debug("Cluster Name: " + dest.getCluster().getName());

			Long physNetId = config.getPhysicalNetworkId();
			PhysicalNetworkTrafficType physNetTT = _physNetTTDao.findBy(physNetId, TrafficType.Guest);
			String kvmNetworkLabel = physNetTT.getKvmNetworkLabel();

			String nwOrchestartionVia = configProp.getProperty(JuniperNDAPIConstants.NW_ORCHESTRATION_VIA);
			s_logger.debug("Traffic label: " + kvmNetworkLabel);

			String vlanValue = null;
			if(networkImplemented.getBroadcastUri() !=null)
			{
				vlanValue = getVlanId(networkImplemented.getBroadcastUri().toString());
			}
			else
			{
				s_logger.error("vlan Id is null");
				throw new CloudRuntimeException(" Network orchestration failed for network "+ config.getName());
			}
 
			s_logger.debug("Vlan assigned for guest network : " + vlanValue);
			
			
			if(nwOrchestartionVia.equals(JuniperNDAPIConstants.NDAPI))	 
			{ 
				s_logger.debug("Performing network orchestration through NDAPI");

				String naasDomainId = configProp.getProperty(JuniperNDAPIConstants.NAASDOMAINID);
				String tenantId = configProp.getProperty(JuniperNDAPIConstants.TENANTID);
				String commanURL = configProp.getProperty(JuniperNDAPIConstants.NDAPI_SERVER_URL);
                String cidr = networkImplemented.getCidr();
                String gateway = networkImplemented.getGateway();
                String[] cidrSplit = networkImplemented.getCidr().split("/");
                String prefix = gateway+"/"+cidrSplit[1];
				String line, jsonString = "";
				String naasServiceId = null;
				String l2csId = null;
				String []ports = null;
				s_logger.debug("Vlan Id: " + vlanValue);
				try {

					//It creates the NaasService
					jsonString = createNaasService(commanURL, tenantId, naasDomainId, vlanValue);

					naasServiceId = getUuid(jsonString,JuniperNDAPIConstants.NAASSERVICE);   

					s_logger.debug("NaasService created: " + naasServiceId);

					String cgId = null;

					//It creates the connectivity group
					jsonString = createConnectivityGroup(commanURL, tenantId, naasServiceId, vlanValue);
					cgId = getUuid(jsonString,JuniperNDAPIConstants.CONNECTIVITY_GROUP); 
					s_logger.debug("Connectivity group created: " + cgId);

					jsonString = getNaasDomain(commanURL, naasDomainId);
					ports = getRevenuePortsForNaasDomain(jsonString);
					
					if(ports != null)
					{
						String portId = null;
						for(String port:ports)
						{			        		
							//It creates the port
							jsonString = createPort(commanURL, tenantId, naasServiceId, cgId, port);
							portId = getUuid(jsonString,JuniperNDAPIConstants.PORT);
							s_logger.debug("Port created: " + portId);
						}
					}
					l2csId = null;
					//It creates the L2CS
					jsonString = createL2CS(commanURL, tenantId, naasServiceId, vlanValue, cgId, prefix);
					l2csId = getUuid(jsonString,JuniperNDAPIConstants.L2CS);
					s_logger.debug("L2CS created: " + l2csId);

					//It activates the NaasService
					jsonString = activateService(commanURL, tenantId, naasServiceId, naasDomainId, vlanValue);
					naasServiceId = getUuid(jsonString,JuniperNDAPIConstants.NAASSERVICE);					
					synchronized (this) {
						try{
							int i = 0;
							String naasServiceJson = null;
							String currentNaasState = null;
							while(i != 10)
							{
								naasServiceJson =getNaasService(commanURL, tenantId, naasServiceId);
								currentNaasState  = getCurrentState(naasServiceJson);
								if(currentNaasState.equals(JuniperNDAPIConstants.ACTIVATED))
								{
									break;
								}						
								i++;
								this.wait(10000);
								s_logger.debug("Waiting to change current state of naas service : " +naasServiceId + " to Activated state");
							}					
							if(currentNaasState.equals(JuniperNDAPIConstants.ACTIVATED))
							{
								s_logger.debug("Activated NaasService: " + naasServiceId);
							}
							else
							{
								throw new CloudRuntimeException("Activation of Naas Service for network "+config.getName()+" failed. ");
							}
						}
						catch(Exception e)
						{
							s_logger.error("Activation of Naas Service for network "+config.getName()+" failed. "+e.getLocalizedMessage());
							throw new CloudRuntimeException("Activation of Naas Service for network "+config.getName()+" failed. "+e.getMessage());
						}
					}

				} catch (Exception e) {
					s_logger.error("Creation of L2 Service failed"+e.getLocalizedMessage());
					throw new CloudRuntimeException("Creation of L2 Service failed"+e.getMessage());
				}
				JuniperNDAPINaasServiceNetworkMapVO naasServiceNetwork = new JuniperNDAPINaasServiceNetworkMapVO(tenantId, naasDomainId, naasServiceId, vlanValue, l2csId,config.getId() );
				_juniperNDAPIDao.persist(naasServiceNetwork);
			}
			else if(nwOrchestartionVia.equals(JuniperNDAPIConstants.NETCONF))
			{
				s_logger.debug("Performing network orchestration through NETCONF");

				Map<String,Set<String>> deviceInterfacessMap = null;

				if(configProp.getProperty(JuniperNDAPIConstants.USE_LLDP).equals("true"))
				{
					StringBuilder strBuilder = new StringBuilder();
					List<String> interfacesTobePartOfVlan = new ArrayList<String>();
					List<String> listOfHostNames = new ArrayList<String>();
					Long clusterId = dest.getCluster().getId();
					List<HostVO> listOfHostForCluster = _hostDao.findByClusterId(clusterId);
					if(listOfHostForCluster != null)
					{
						for(HostVO host : listOfHostForCluster)
						{
							listOfHostNames.add(host.getName());
						}
					}

					String switchesLable = configProp.getProperty(JuniperNDAPIConstants.SWITCHES);
					String[] switches = switchesLable.split(",");
                    
					s_logger.debug("Using LLDP to discover interfaces connected to neighbors");
					
					for(String jSwitch : switches)
					{
						Map<String,String> interfaceSystemMap = LLDPNeighbors.getNeighborsInterfaceSystemMap(jSwitch,configProp.getProperty(JuniperNDAPIConstants.DEVICE_USER),configProp.getProperty(JuniperNDAPIConstants.DEVICE_PASSWORD));

						if(interfaceSystemMap != null || !interfaceSystemMap.isEmpty())
						{
							for(String host:listOfHostNames)
							{
								if(interfaceSystemMap.containsKey(host))
								{
									interfacesTobePartOfVlan.add(interfaceSystemMap.get(host));
									strBuilder.append(jSwitch);
									strBuilder.append(":");
									strBuilder.append(interfaceSystemMap.get(host));
									strBuilder.append(",");
								}
							}
						}
					}
					strBuilder.deleteCharAt(strBuilder.length()-1);
					deviceInterfacessMap = DeviceInterfacesMap.getDeviceInterfaceMap(strBuilder.toString());
				}
				else
				{
					String portsAssignedtoTrafficlabel = configProp.getProperty(kvmNetworkLabel).trim();
					deviceInterfacessMap = DeviceInterfacesMap.getDeviceInterfaceMap(portsAssignedtoTrafficlabel);
				}	
				Set<String> devices = deviceInterfacessMap.keySet();
				for(String deviceName : devices)
				{
					try
					{
						String hardwareModel = ShowHardwareModel.getHardwareModel(deviceName,configProp.getProperty(JuniperNDAPIConstants.DEVICE_USER),configProp.getProperty(JuniperNDAPIConstants.DEVICE_PASSWORD));
						s_logger.debug("Hardware model for device "+deviceName+" is " +hardwareModel);
						Device device = new Device(deviceName,configProp.getProperty(JuniperNDAPIConstants.DEVICE_USER),configProp.getProperty(JuniperNDAPIConstants.DEVICE_PASSWORD),null);
						StringBuilder commandList = new StringBuilder();
						String listOfelsSupportedDevices = configProp.getProperty(JuniperNDAPIConstants.ELS_SUPPORTED_DEVICES);
						String[] elsSupportedDevices = listOfelsSupportedDevices.split(",");
						if(isELS2Supported(hardwareModel, elsSupportedDevices))
						//if(isELS2Supported(hardwareModel))
						{
							commandList.append(ELS_JunosL2XMLConfigs.getInstance().CREATE_VLAN.substitute("vlan"+vlanValue,vlanValue));
							Set<String> interfaces = deviceInterfacessMap.get(deviceName);
							for(String deviceInterface: interfaces)
							{
								commandList.append(ELS_JunosL2XMLConfigs.getInstance().CREATE_TRUNK_PORT_WITH_VLAN_MEMBER.substitute(deviceInterface,"0","vlan"+vlanValue));
							}

						}
						else
						{
							commandList.append(JunosL2XMLConfigs.getInstance().CREATE_VLAN.substitute("vlan"+vlanValue,vlanValue));
							Set<String> interfaces = deviceInterfacessMap.get(deviceName);
							for(String deviceInterface: interfaces)
							{
								commandList.append(JunosL2XMLConfigs.getInstance().CREATE_TRUNK_PORT_WITH_VLAN_MEMBER.substitute(deviceInterface,"0","vlan"+vlanValue));
							}
						}
						NetconfOperations.commit(device,commandList.toString());
					}
					catch(Exception e)
					{
						s_logger.error("Orchestration through Netconf failed" +e.getLocalizedMessage());
						throw new CloudRuntimeException("Orchestration through Netconf failed"+e.getMessage());
					}
				}				
			}
			return networkImplemented;
		}

		DataCenter zone = dest.getDataCenter();
		NetworkVO implemented = new NetworkVO(config.getTrafficType(), config.getMode(), config.getBroadcastDomainType(), config.getNetworkOfferingId(), State.Allocated,
				config.getDataCenterId(), config.getPhysicalNetworkId());

		// Get a vlan tag
//		int vlanTag;
//		if (config.getBroadcastUri() == null) {
//			String vnet = _dcDao.allocateVnet(zone.getId(), config.getPhysicalNetworkId(), config.getAccountId(), context.getReservationId());
//
//			try {
//				vlanTag = Integer.parseInt(vnet);
//			} catch (NumberFormatException e) {
//				throw new CloudRuntimeException("Obtained an invalid guest vlan tag. Exception: " + e.getMessage());
//			}
//
//			implemented.setBroadcastUri(BroadcastDomainType.Vlan.toUri(vlanTag));
//			ActionEventUtils.onCompletedActionEvent(UserContext.current().getCallerUserId(), config.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_ZONE_VLAN_ASSIGN, "Assigned Zone Vlan: " + vnet + " Network Id: " + config.getId(), 0);
//		} else {
//			vlanTag = Integer.parseInt(config.getBroadcastUri().getHost());
//			implemented.setBroadcastUri(config.getBroadcastUri());
//		}
//
//		// Determine the offset from the lowest vlan tag
//		int offset = getVlanOffset(config.getPhysicalNetworkId(), vlanTag);
//
//		// Determine the new gateway and CIDR
//		String[] oldCidr = config.getCidr().split("/");
//		String oldCidrAddress = oldCidr[0];
//		int cidrSize = getGloballyConfiguredCidrSize();
//
//		// If the offset has more bits than there is room for, return null
//		long bitsInOffset = 32 - Integer.numberOfLeadingZeros(offset);
//		if (bitsInOffset > (cidrSize - 8)) {
//			throw new CloudRuntimeException("The offset " + offset + " needs " + bitsInOffset + " bits, but only have " + (cidrSize - 8) + " bits to work with.");
//		}
//
//		long newCidrAddress = (NetUtils.ip2Long(oldCidrAddress) & 0xff000000) | (offset << (32 - cidrSize));
//		implemented.setGateway(NetUtils.long2Ip(newCidrAddress + 1));
//		implemented.setCidr(NetUtils.long2Ip(newCidrAddress) + "/" + cidrSize);
//		implemented.setState(State.Implemented);
//
//		// Mask the Ipv4 address of all nics that use this network with the new guest VLAN offset
//		List<NicVO> nicsInNetwork = _nicDao.listByNetworkId(config.getId());
//		for (NicVO nic : nicsInNetwork) {
//			if (nic.getIp4Address() != null) {
//				long ipMask = getIpMask(nic.getIp4Address(), cidrSize);
//				nic.setIp4Address(NetUtils.long2Ip(newCidrAddress | ipMask));
//				_nicDao.persist(nic);
//			}
//		}       
//
//		// Mask the destination address of all port forwarding rules in this network with the new guest VLAN offset
//		List<PortForwardingRuleVO> pfRulesInNetwork = _pfRulesDao.listByNetwork(config.getId());
//		for (PortForwardingRuleVO pfRule : pfRulesInNetwork) {
//			if (pfRule.getDestinationIpAddress() != null) {
//				long ipMask = getIpMask(pfRule.getDestinationIpAddress().addr(), cidrSize);
//				String maskedDestinationIpAddress = NetUtils.long2Ip(newCidrAddress | ipMask);
//				pfRule.setDestinationIpAddress(new Ip(maskedDestinationIpAddress));
//				_pfRulesDao.update(pfRule.getId(), pfRule);
//			}
//		}

		return implemented;
	}
	
	private boolean isELS2Supported(String hardwareModel)
	{
		if(hardwareModel.contains("ex4300")||hardwareModel.contains("ex9200")||hardwareModel.contains("qfx5100"))
		{
		  return true;
		}
		else
		{
			return false;
		}
	}
	
	private boolean isELS2Supported(String hardwareModel,String[] elsSupportedDevices)
	{	 	
		boolean isELSDevice = false;
        for(String deviceName:elsSupportedDevices)
        {
        	if(hardwareModel.contains(deviceName))
        	{
        		isELSDevice = true;
        	}
        }
        return isELSDevice;
	}

	@Override
	public NicProfile allocate(Network config, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm) throws InsufficientVirtualNetworkCapcityException,
	InsufficientAddressCapacityException {

		if (_networkModel.networkIsConfiguredForExternalNetworking(config.getDataCenterId(), config.getId()) && nic != null && nic.getRequestedIpv4() != null) {
			throw new CloudRuntimeException("Does not support custom ip allocation at this time: " + nic);
		}

		NicProfile profile = super.allocate(config, nic, vm);

		boolean _isEnabled = Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()));
		if (_isEnabled) {
			return null;
		}

		if (_networkModel.networkIsConfiguredForExternalNetworking(config.getDataCenterId(), config.getId())) {
			profile.setStrategy(ReservationStrategy.Start);
			/* We won't clear IP address, because router may set gateway as it IP, and it would be updated properly later */
			//profile.setIp4Address(null);
			profile.setGateway(null);
			profile.setNetmask(null);
		}

		return profile;
	}

	@Override
	public void reserve(NicProfile nic, Network config,
			VirtualMachineProfile<? extends VirtualMachine> vm,
			DeployDestination dest, ReservationContext context)
					throws InsufficientVirtualNetworkCapcityException,
					InsufficientAddressCapacityException {
		s_logger.debug("reserve called with network: " + config.toString() + " nic: " + nic.toString() + " vm: " + vm.toString());

		if(vm.getType().toString().equals("User"))
		{
			if(nic.getBroadCastUri() != null)
			{
				String uri = config.getBroadcastUri().toString();
				String [] uriSpilt = uri.split("/");
				String vlanValue = uriSpilt[2];   
				s_logger.debug("Guest network name  :" + config.getName());
				s_logger.debug("Vlan assigned for Guest network ["+config.getName()+"] is: "+vlanValue);

				String ipAdress = nic.getIp4Address();
				s_logger.debug("IP adress of User VM ["+vm.getHostName()+"] is : " + ipAdress);
			}
		}

		boolean _isEnabled = Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()));
		if (_isEnabled) {
			return;
		}

		DataCenter dc = _dcDao.findById(config.getDataCenterId());

		if (_networkModel.networkIsConfiguredForExternalNetworking(config.getDataCenterId(), config.getId())) {
			nic.setBroadcastUri(config.getBroadcastUri());
			nic.setIsolationUri(config.getBroadcastUri());
			nic.setDns1(dc.getDns1());
			nic.setDns2(dc.getDns2());
			nic.setNetmask(NetUtils.cidr2Netmask(config.getCidr()));
			long cidrAddress = NetUtils.ip2Long(config.getCidr().split("/")[0]);
			int cidrSize = getGloballyConfiguredCidrSize();
			nic.setGateway(config.getGateway());

			if (nic.getIp4Address() == null) {
				String guestIp = _networkMgr.acquireGuestIpAddress(config, null);
				if (guestIp == null) {
					throw new InsufficientVirtualNetworkCapcityException("Unable to acquire guest IP address for network " + config, DataCenter.class, dc.getId());
				}

				nic.setIp4Address(guestIp);
			} else {
				long ipMask = NetUtils.ip2Long(nic.getIp4Address()) & ~(0xffffffffffffffffl << (32 - cidrSize));
				nic.setIp4Address(NetUtils.long2Ip(cidrAddress | ipMask));
			}
		} else {
			super.reserve(nic, config, vm, dest, context);
		}
	}

	@Override
	public void shutdown(NetworkProfile profile, NetworkOffering offering) {
		s_logger.debug("shutdown called");
		loadProerties();
		
		String vlanValue = null;
		if(profile.getBroadcastUri() != null)
		{
			vlanValue = getVlanId(profile.getBroadcastUri().toString());
		}
		super.shutdown(profile, offering);
		Long physNetId = profile.getPhysicalNetworkId();
		PhysicalNetworkTrafficType physNetTT = _physNetTTDao.findBy(physNetId, TrafficType.Guest);
		String kvmNetworkLabel = physNetTT.getKvmNetworkLabel();
		String nwOrchestartionVia = configProp.getProperty(JuniperNDAPIConstants.NW_ORCHESTRATION_VIA);
		String portsAssignedtoTrafficlabel = configProp.getProperty(kvmNetworkLabel);
		
			
        if(nwOrchestartionVia.equals(JuniperNDAPIConstants.NETCONF))
		{
        	if(vlanValue != null){
        		Map<String,Set<String>> deviceInterfacessMap = null;
        		if(configProp.getProperty(JuniperNDAPIConstants.USE_LLDP).equals("true"))
        		{
        			StringBuilder strBuilder = new StringBuilder();
        			List<String> interfacesTobePartOfVlan = new ArrayList<String>();
        			List<String> listOfHostNames = new ArrayList<String>();
        			List<DataCenterVO> listOfEnabledZone = _zoneDao.listEnabledZones();
        			for(DataCenterVO dataCenter :listOfEnabledZone)
        			{
        				long dataCenterId = dataCenter.getId();
        				List<HostVO> listOfHostForDatacenter = _hostDao.listByDataCenterId(dataCenterId);
        				if(listOfHostForDatacenter != null)
        				{
        					for(HostVO host : listOfHostForDatacenter)
        					{
        						listOfHostNames.add(host.getName());
        					}
        				}
        			}
        
        			String switchesLable = configProp.getProperty(JuniperNDAPIConstants.SWITCHES);
        			String[] switches = switchesLable.split(",");

        			s_logger.debug("Using LLDP to discover interfaces connected to neighbors");

        			for(String jSwitch : switches)
        			{
        				Map<String,String> interfaceSystemMap = LLDPNeighbors.getNeighborsInterfaceSystemMap(jSwitch,configProp.getProperty(JuniperNDAPIConstants.DEVICE_USER),configProp.getProperty(JuniperNDAPIConstants.DEVICE_PASSWORD));

        				if(interfaceSystemMap != null || !interfaceSystemMap.isEmpty())
        				{
        					for(String host:listOfHostNames)
        					{
        						if(interfaceSystemMap.containsKey(host))
        						{
        							interfacesTobePartOfVlan.add(interfaceSystemMap.get(host));
        							strBuilder.append(jSwitch);
        							strBuilder.append(":");
        							strBuilder.append(interfaceSystemMap.get(host));
        							strBuilder.append(",");
        						}
        					}
        				}
        			}
        			strBuilder.deleteCharAt(strBuilder.length()-1);
        			deviceInterfacessMap = DeviceInterfacesMap.getDeviceInterfaceMap(strBuilder.toString());
        		}
        		else
        		{
        			deviceInterfacessMap = DeviceInterfacesMap.getDeviceInterfaceMap(portsAssignedtoTrafficlabel);
        		}
        		Set<String> devices = deviceInterfacessMap.keySet();
        		for(String deviceName : devices)
        		{
        			try
        			{
        				Device device = new Device(deviceName,configProp.getProperty(JuniperNDAPIConstants.DEVICE_USER),configProp.getProperty(JuniperNDAPIConstants.DEVICE_PASSWORD),null);
        				StringBuilder commandList = new StringBuilder();
        				Set<String> interfaces = deviceInterfacessMap.get(deviceName);
        				for(String deviceInterface: interfaces)
        				{
        					commandList.append(JunosL2XMLConfigs.getInstance().DELETE_VLAN_MEMBER.substitute(deviceInterface,"0","vlan"+vlanValue));
        				}
        				commandList.append(JunosL2XMLConfigs.getInstance().DELETE_VLAN.substitute("vlan"+vlanValue));
        				NetconfOperations.commit(device,commandList.toString());
        				s_logger.debug("Deleted vlan : " +vlanValue+ " from device " +deviceName);
        			}
        			catch(Exception e)
        			{
        				s_logger.error("Orchestration through Netconf failed"+e.getLocalizedMessage());
        				throw new CloudRuntimeException("Orchestration through Netconf failed"+e.getMessage());
        			}
        		}
        	}
		}
		
		else if(nwOrchestartionVia.equals(JuniperNDAPIConstants.NDAPI))
		{
			JuniperNDAPINaasServiceNetworkMapVO ndapiDao = _juniperNDAPIDao.findOneByNetworkId(profile.getId());
			if(ndapiDao != null)
			{
				String commanURL = configProp.getProperty(JuniperNDAPIConstants.NDAPI_SERVER_URL);
				String naasServiceId = ndapiDao.getNaasId();
				String tenantId = ndapiDao.getTenantId();
				String naasDomainId = ndapiDao.getNaasDomainId();
				String vlanId = ndapiDao.getVlanId();
				try{
					String json =getNaasService(commanURL, tenantId, naasServiceId);
					String currentState  = getCurrentState(json);
					if(currentState.equals(JuniperNDAPIConstants.ACTIVATED))
					{
						s_logger.debug("Deactvating the nass service : " +naasServiceId);
						deActivateService(commanURL, tenantId, naasServiceId, naasDomainId, vlanId);
					}
					
					synchronized (this) {
						try{
							int i = 0;
							String naasServiceJson = null;
							String currentNaasState = null;
							while(i != 10)
							{
								naasServiceJson =getNaasService(commanURL, tenantId, naasServiceId);
								currentNaasState  = getCurrentState(naasServiceJson);
								if(currentNaasState.equals(JuniperNDAPIConstants.DEFINED))
								{
									break;
								}						
								i++;
								this.wait(10000);
								s_logger.debug("Waiting to change current state of naas service : " +naasServiceId + " to DEFINED state");
							}					
							if(currentNaasState.equals(JuniperNDAPIConstants.DEFINED))
							{
								deleteNaasService(commanURL, tenantId, naasServiceId);
								s_logger.debug("Deleting the nass service : " +naasServiceId);
								boolean value = _juniperNDAPIDao.deleteNaasServiceNetworkMapByNeworkId(profile.getId());
							}
							else
							{
								throw new CloudRuntimeException("Deactivation of Naas Service for network failed. ");
							}
						}
						catch(Exception e)
						{
							s_logger.error("Deletion of Naas Service for network failed"+e.getLocalizedMessage());
							throw new CloudRuntimeException("Deletion of Naas Service for network failed"+e.getMessage());
						}
					}
				}
				catch (Exception e)
				{
					s_logger.error("Deactivation of Naas Service failed"+e.getLocalizedMessage());
					throw new CloudRuntimeException("Deactivation of Naas Service failed"+e.getMessage());
				}				
			}
		}
	}

	@Override
	public boolean trash(Network network, NetworkOffering offering,
			Account owner) {
		s_logger.debug("trash called with network: " + network.toString());

		return super.trash(network, offering, owner);
	}

	@Override @DB
	public void deallocate(Network config, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm) {
		super.deallocate(config, nic, vm);

		if (Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()))) {
			return;
		}

		if (_networkModel.networkIsConfiguredForExternalNetworking(config.getDataCenterId(), config.getId())) {
			nic.setIp4Address(null);
			nic.setGateway(null);
			nic.setNetmask(null);
			nic.setBroadcastUri(null);
			nic.setIsolationUri(null);
		}
	}

	@Override
	public boolean release(NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, String reservationId) {

		if (Boolean.parseBoolean(_configDao.getValue(Config.OvsTunnelNetwork.key()))) {
			return true;
		}

		NetworkVO network = _networkDao.findById(nic.getNetworkId());

		if (network != null && _networkModel.networkIsConfiguredForExternalNetworking(network.getDataCenterId(), network.getId())) {
			return true;
		} else {
			return super.release(nic, vm, reservationId);
		}
	}

	private long getIpMask(String ipAddress, long cidrSize) {
		return NetUtils.ip2Long(ipAddress) & ~(0xffffffffffffffffl << (32 - cidrSize));
	}
}
