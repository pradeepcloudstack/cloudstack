package com.cloud.network.naas.dao;


import java.util.List;


import com.cloud.utils.db.GenericDao;
import com.cloud.network.JuniperNDAPINaasServiceNetworkMapVO;


public interface JuniperNDAPINaasServiceNetworkMapDao  extends GenericDao<JuniperNDAPINaasServiceNetworkMapVO, Long>{
	
	JuniperNDAPINaasServiceNetworkMapVO findOneByNaasId(long naasId);
	JuniperNDAPINaasServiceNetworkMapVO findOneByNetworkId(long networkId);
	JuniperNDAPINaasServiceNetworkMapVO persist(JuniperNDAPINaasServiceNetworkMapVO naasServiceNetwork);
	boolean deleteNaasServiceNetworkMapByNeworkId(long networkId);


}
